package com.mlbb.counteroverlay.data

import com.mlbb.counteroverlay.data.model.CounterStat
import com.mlbb.counteroverlay.data.model.HeroUi
import com.mlbb.counteroverlay.data.model.Lane
import com.mlbb.counteroverlay.data.model.RateUi
import com.mlbb.counteroverlay.data.model.Roster

/** Pure draft logic, kept separate from Android so it can be unit-tested. */
object DraftAdvisor {

    /** A counter pick with how much to trust it. */
    data class Pick(
        val stat: CounterStat,
        /** 0..1: how many games the number is based on, relative to a typical matchup. */
        val confidence: Double,
        /** Listed by the in-game guide as a counter to this enemy. */
        val official: Boolean,
    ) {
        val heroId get() = stat.heroId
        val edge get() = stat.winRateEdge
        val isLowData get() = !official && confidence < LOW_DATA
    }

    data class LaneCounters(val lane: Lane, val isSameLane: Boolean, val picks: List<Pick>)
    data class LanePicks(val lane: Lane, val heroes: List<Pair<HeroUi, RateUi>>)

    /** A pick scored against a whole enemy team. */
    data class TeamPick(
        val heroId: Int,
        val averageEdge: Double,
        val beats: Int,          // enemies this hero has a positive edge against
        val enemies: Int,        // enemies with matchup data
        val perEnemy: Map<Int, Double>,
        val score: Double = averageEdge,
    )

    // ------------------------------------------------------------------ reliability

    /**
     * Matchup numbers for rarely played heroes come from few games and swing wildly
     * (e.g. Lolita, picked in ~0.08% of games). Games where both heroes meet scale
     * with pickRateA × pickRateB, so confidence = n / (n + N0), where N0 is set so a
     * typical pair (both ~0.7%) gets ~0.8 and very rare pairs get close to 0.
     */
    private const val N0 = 1.2e-5
    const val LOW_DATA = 0.35
    const val RARE_HERO_PICK_RATE = 0.002

    fun confidence(a: HeroUi?, b: HeroUi?): Double {
        val pa = a?.pickRate ?: return 0.5
        val pb = b?.pickRate ?: return 0.5
        val n = pa * pb
        return n / (n + N0)
    }

    fun isRarelyPlayed(h: HeroUi) = (h.pickRate ?: 1.0) < RARE_HERO_PICK_RATE

    /**
     * Ranking score: the edge shrunk toward 0 by confidence. In-game guide picks count as
     * fully reliable, and for a rarely played enemy (whose stats are mostly noise) they go first.
     */
    private fun score(p: Pick, enemyIsRare: Boolean) = when {
        p.official && enemyIsRare -> 1.0 + p.edge
        p.official -> p.edge.coerceAtLeast(0.0) + 0.005
        else -> p.edge * p.confidence
    }

    // ------------------------------------------------------------------ single enemy

    /**
     * Counter picks against [enemy], most reliable first. Heroes the in-game guide says
     * the enemy is strong against are never suggested (data and guide must agree).
     */
    fun counterPicks(enemy: HeroUi, matchups: List<CounterStat>, roster: Roster): List<Pick> {
        val officialStrong = enemy.strongAgainst.toSet()
        val officialWeak = enemy.weakAgainst.toSet()
        return matchups.asSequence()
            .filter { it.heroId !in officialStrong }
            .map { Pick(it, confidence(enemy, roster.byId[it.heroId]), it.heroId in officialWeak) }
            .filter { it.edge > 0 || (it.official && it.edge > -0.01) }
            .sortedByDescending { score(it, isRarelyPlayed(enemy)) }
            .toList()
    }

    /**
     * Best counters to [enemy], grouped by lane. The enemy's own lane comes first,
     * so "EXP vs EXP" and "Gold vs Gold" matchups are the first thing you see.
     */
    fun countersByLane(enemy: HeroUi, matchups: List<CounterStat>, roster: Roster, perLane: Int = 3): List<LaneCounters> {
        val picks = counterPicks(enemy, matchups, roster)
        val enemyLanes = enemy.laneEnums
        val order = enemyLanes + Lane.entries.filterNot { it in enemyLanes }
        return order.map { lane ->
            LaneCounters(lane, lane in enemyLanes,
                picks.filter { roster.byId[it.heroId]?.laneEnums?.contains(lane) == true }.take(perLane))
        }.filter { it.picks.isNotEmpty() }
    }

    /** Heroes that do worst against [enemy]: the in-game guide's list first, then the data. */
    fun avoid(enemy: HeroUi, matchups: List<CounterStat>, roster: Roster, count: Int = 4): List<Pick> {
        val byId = matchups.associateBy { it.heroId }
        val official = enemy.strongAgainst.mapNotNull { id ->
            Pick(byId[id] ?: CounterStat(id, 0.0), 1.0, official = true)
        }
        val data = matchups.asSequence()
            .filter { it.winRateEdge < 0 && it.heroId !in enemy.strongAgainst }
            .map { Pick(it, confidence(enemy, roster.byId[it.heroId]), false) }
            .sortedBy { it.edge * it.confidence }
            .toList()
        return (official + data).take(count)
    }

    // ------------------------------------------------------------------ whole team

    /**
     * Best picks against several enemies at once: average confidence-weighted edge
     * across every enemy, so a hero that is good into the whole team beats one that
     * hard-counters only one of them. Heroes already on the enemy team, and heroes the
     * in-game guide says an enemy is strong against, are excluded.
     */
    fun teamCounters(enemyIds: List<Int>, matchupsByEnemy: Map<Int, List<CounterStat>>, roster: Roster): List<TeamPick> {
        val tables = enemyIds.mapNotNull { id -> matchupsByEnemy[id]?.takeIf { it.isNotEmpty() }?.let { id to it.associateBy(CounterStat::heroId) } }
        if (tables.isEmpty()) return emptyList()
        val enemies = enemyIds.mapNotNull { roster.byId[it] }
        val banned = enemyIds.toSet() + enemies.flatMap { it.strongAgainst }
        val candidates = tables.flatMap { it.second.keys }.toSet() - banned
        return candidates.mapNotNull { c ->
            val per = tables.mapNotNull { (enemy, table) -> table[c]?.let { enemy to it.winRateEdge } }.toMap()
            if (per.size < tables.size) return@mapNotNull null   // needs data against every enemy
            val weighted = per.map { (enemy, edge) ->
                val official = roster.byId[enemy]?.weakAgainst?.contains(c) == true
                if (official) edge.coerceAtLeast(0.0) + 0.005 else edge * confidence(roster.byId[enemy], roster.byId[c])
            }
            TeamPick(c, per.values.average(), per.values.count { it > 0 }, per.size, per, weighted.average())
        }.sortedWith(compareByDescending<TeamPick> { it.score }.thenByDescending { it.beats })
    }

    fun teamPicksByLane(picks: List<TeamPick>, roster: Roster, perLane: Int = 2): List<Pair<Lane, List<TeamPick>>> =
        Lane.entries.map { lane ->
            lane to picks.filter { it.score > 0 && roster.byId[it.heroId]?.laneEnums?.contains(lane) == true }.take(perLane)
        }.filter { it.second.isNotEmpty() }

    // ------------------------------------------------------------------ first pick

    /**
     * Safe first picks per lane: highest recent win rate among heroes that are
     * played often enough for the number to be reliable. [rates] overrides the
     * roster's all-rank rates (used by the rank filter).
     */
    fun firstPicks(roster: Roster, rates: Map<Int, RateUi>? = null, perLane: Int = 4, minPickRate: Double = 0.002): List<LanePicks> {
        fun rateOf(h: HeroUi) = rates?.get(h.id) ?: RateUi(h.winRate, h.banRate, h.pickRate)
        return Lane.entries.map { lane ->
            LanePicks(
                lane,
                roster.heroes.asSequence()
                    .filter { lane in it.laneEnums }
                    .map { it to rateOf(it) }
                    .filter { (_, r) -> r.winRate != null && (r.pickRate ?: 0.0) >= minPickRate }
                    .sortedByDescending { it.second.winRate }
                    .take(perLane)
                    .toList(),
            )
        }.filter { it.heroes.isNotEmpty() }
    }

    // ------------------------------------------------------------------ reasons

    /**
     * Short, plain-English reasons why [pick] does well into [enemy], derived only
     * from official skill tags and specialities. Returns at most [max] reasons, or
     * none when the tags don't explain it (the win-rate line is always shown).
     */
    fun reasons(pick: HeroUi, enemy: HeroUi, max: Int = 2): List<String> {
        val p = pick.traits.toSet(); val e = enemy.traits.toSet()
        val eName = enemy.name
        val enemyMobile = "Mobility" in e || "Teleport" in e || "Chase" in e
        val enemyBurst = "Burst" in e || "Finisher" in e
        val enemyCc = "Crowd Control" in e || "Control" in e
        val enemyHeals = "Heal" in e || "Regen" in e
        val enemyDiver = "Charge" in e || "Initiator" in e || "Chase" in e
        val enemySquishy = enemy.roles.any { it == "Marksman" || it == "Mage" }
        val enemyAssassin = "Assassin" in enemy.roles
        val pickCc = "Crowd Control" in p || "Control" in p
        val pickDefense = p.any { it in setOf("Shield", "Reduce DMG", "Invincible", "Death Immunity", "Guard") }

        val out = mutableListOf<String>()
        if (enemyMobile && pickCc) out += "Strong crowd control catches $eName's dashes"
        if (enemyBurst && pickDefense) out += "Shields and damage reduction absorb $eName's burst"
        if (enemyCc && ("Remove CC" in p || "CC Immune" in p)) out += "Can cleanse or ignore $eName's crowd control"
        if (enemyHeals && ("Burst" in p || "Finisher" in p)) out += "Burst damage outpaces $eName's healing"
        if (enemySquishy && ("Chase" in p || "Charge" in p || "Initiator" in p)) out += "Can reach $eName in the backline"
        if (enemyDiver && "Poke" in p) out += "Pokes $eName from a safe range"
        if (enemyMobile && "Slow" in p && out.none { "dashes" in it }) out += "Slows keep $eName from escaping"
        if (enemyAssassin && ("Guard" in p || "Tank" in pick.roles) && out.none { "absorb" in it }) out += "Tanky enough to survive $eName's attacks"
        return out.take(max)
    }
}
