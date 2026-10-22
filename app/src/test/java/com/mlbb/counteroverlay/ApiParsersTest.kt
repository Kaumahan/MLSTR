package com.mlbb.counteroverlay

import com.mlbb.counteroverlay.data.DraftAdvisor
import com.mlbb.counteroverlay.data.HeroSearch
import com.mlbb.counteroverlay.data.model.Lane
import com.mlbb.counteroverlay.data.model.Roster
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import com.mlbb.counteroverlay.data.remote.ApiFormatException
import com.mlbb.counteroverlay.data.remote.ApiParsers
import com.mlbb.counteroverlay.data.remote.NetworkModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs parsers, search and draft logic against real responses captured from arena.rone.dev. */
class ApiParsersTest {
    private val json = NetworkModule.json
    private fun res(name: String) = javaClass.classLoader!!.getResource(name).readText()

    private val seed: Roster by lazy {
        json.decodeFromString(Roster.serializer(), File("src/main/assets/roster_seed.json").readText())
    }

    private val roster: Roster by lazy {
        val heroes = ApiParsers.parseHeroPage(json, res("positions.json")).heroes
            .map { it.copy(traits = seed.byId[it.id]?.traits.orEmpty()) }
        val icons = ApiParsers.parseHeroIcons(json, res("heroes.json"))
        val rates = ApiParsers.parseRates(json, res("rank.json"))
        Roster(
            heroes.map { h -> rates[h.id].let { r -> h.copy(icon = icons[h.id], winRate = r?.winRate, banRate = r?.banRate, pickRate = r?.pickRate) } },
            ApiParsers.parseItems(json, res("equipment.json")),
        )
    }

    @Test fun parsesFullRosterWithIconsRatesAndLanes() {
        assertTrue(roster.heroes.size >= 133)
        assertTrue(roster.heroes.all { it.icon != null })
        assertTrue(roster.heroes.count { it.winRate != null } >= 130)
        assertTrue("every lane is recognised", roster.heroes.all { it.laneEnums.size == it.lanes.size && it.lanes.isNotEmpty() })
        val lukas = roster.heroes.first { it.name == "Lukas" }
        println("Lukas -> ${lukas.subtitle}  win=${lukas.winRate}")
        assertTrue(roster.items.size > 100)
    }

    @Test fun typoTolerantSearch() {
        fun top(q: String) = HeroSearch.search(roster.heroes, q).firstOrNull()?.name
        assertEquals("Lukas", top("lucas"))
        assertEquals("Lukas", top("Lukas"))
        assertEquals("Fanny", top("fany"))
        assertEquals("Fanny", top("fnany"))
        assertEquals("Granger", top("granjer"))
        assertEquals("X.Borg", top("xborg"))
        assertEquals("Yu Zhong", top("yz"))
        assertTrue(HeroSearch.search(roster.heroes, "mage").all { "Mage" in it.roles || it.name.lowercase().startsWith("mage") })
        assertTrue(HeroSearch.search(roster.heroes, "gold").size >= 15)
        assertTrue(HeroSearch.search(roster.heroes, "qqqqzz").isEmpty())
    }

    private fun hero(name: String) = roster.heroes.first { it.name == name }
    private fun names(ids: List<Int>) = ids.map { roster.byId[it]?.name }

    @Test fun matchupDirectionAgreesWithInGameGuide() {
        // Regression: v1.3 showed heroes the enemy BEATS as its counters (e.g. Chang'e/Harley vs Lolita).
        // The guide and ranked stats disagree on some single heroes, so check the combined direction.
        val counters = mutableListOf<Double>(); val victims = mutableListOf<Double>()
        for ((file, enemyName) in listOf("matchups_fanny.json" to "Fanny", "matchups_84.json" to "Ling",
                                          "matchups_1.json" to "Miya", "matchups_20.json" to "Lolita")) {
            val enemy = hero(enemyName)
            val table = ApiParsers.parseMatchups(json, res(file)).associateBy { it.heroId }
            counters += enemy.weakAgainst.mapNotNull { table[it]?.winRateEdge }
            victims += enemy.strongAgainst.mapNotNull { table[it]?.winRateEdge }
        }
        println("guide counters avg %+.2f%%, heroes the enemy beats avg %+.2f%%".format(counters.average() * 100, victims.average() * 100))
        assertTrue(counters.average() > 0)
        assertTrue(victims.average() < 0)
    }

    @Test fun lolitaCountersAreNotProjectileMagesSheBlocks() {
        val lolita = hero("Lolita")
        val matchups = ApiParsers.parseMatchups(json, res("matchups_20.json"))
        val picks = DraftAdvisor.counterPicks(lolita, matchups, roster).take(8).map { roster.byId[it.heroId]!!.name }
        println("Lolita counters -> $picks")
        listOf("Cyclops", "Chang'e", "Harley", "Kimmy").forEach { assertTrue("$it must not be a Lolita counter", it !in picks) }
        assertTrue("Lolita's guide counters are ranked first", picks.take(2).toSet() == names(lolita.weakAgainst).toSet())
        val avoid = DraftAdvisor.avoid(lolita, matchups, roster).map { roster.byId[it.heroId]!!.name }
        println("Don't pick into Lolita -> $avoid")
        assertTrue("Cyclops" in avoid)
        assertTrue(DraftAdvisor.isRarelyPlayed(lolita))
    }

    @Test fun laneCountersPutSameLaneFirst() {
        val fanny = hero("Fanny")
        val matchups = ApiParsers.parseMatchups(json, res("matchups_fanny.json"))
        assertTrue(matchups.size > 100)
        assertEquals(matchups.sortedByDescending { it.winRateEdge }, matchups)
        val byLane = DraftAdvisor.countersByLane(fanny, matchups, roster)
        assertEquals(Lane.JUNGLE, byLane.first().lane)
        assertTrue(byLane.first().isSameLane)
        byLane.forEach { lc ->
            println("${lc.lane} -> " + lc.picks.joinToString { "${roster.byId[it.heroId]?.name} +%.1f%% c=%.2f%s".format(it.edge * 100, it.confidence, if (it.official) " ★" else "") })
            assertTrue(lc.picks.all { roster.byId[it.heroId]!!.laneEnums.contains(lc.lane) })
            assertTrue(lc.picks.none { it.heroId in fanny.strongAgainst })
        }
        val all = DraftAdvisor.counterPicks(fanny, matchups, roster).map { roster.byId[it.heroId]!!.name }
        listOf("Granger", "Clint").forEach { assertTrue("Fanny beats $it; must not be suggested", it !in all.take(15)) }
    }

    @Test fun parsesBuildsWithItemNames() {
        val builds = ApiParsers.parseBuilds(json, res("builds_fanny_jungle.json"))
        assertTrue(builds.isNotEmpty())
        val names = builds.first().itemIds.map { roster.items[it]?.name }
        println("Fanny build -> $names, ${builds.first().emblem}, ${builds.first().spell}")
        assertTrue(names.all { it != null })
    }

    @Test fun firstPicksPerLane() {
        val picks = DraftAdvisor.firstPicks(roster)
        assertEquals(5, picks.size)
        picks.forEach { lp ->
            println("${lp.lane} -> " + lp.heroes.joinToString { "${it.first.name} %.1f%%".format(it.second.winRate!! * 100) })
            assertTrue(lp.heroes.zipWithNext().all { (a, b) -> a.second.winRate!! >= b.second.winRate!! })
        }
    }

    @Test fun rankFilterChangesMatchups() {
        val all = ApiParsers.parseMatchups(json, res("matchups_fanny.json"))
        val mythic = ApiParsers.parseMatchups(json, res("matchups_fanny_mythic.json"))
        assertTrue(mythic.size > 100)
        assertTrue("mythic ranking differs from all-rank", all.take(5).map { it.heroId } != mythic.take(5).map { it.heroId })
    }

    @Test fun teamCountersCoverWholeTeam() {
        val names = mapOf(17 to "Fanny", 84 to "Ling", 1 to "Miya")
        val tables = mapOf(
            17 to ApiParsers.parseMatchups(json, res("matchups_fanny.json")),
            84 to ApiParsers.parseMatchups(json, res("matchups_84.json")),
            1 to ApiParsers.parseMatchups(json, res("matchups_1.json")),
        )
        val picks = DraftAdvisor.teamCounters(listOf(17, 84, 1), tables, roster)
        assertTrue(picks.size > 50)
        assertTrue("enemies excluded", picks.none { it.heroId in names.keys })
        assertTrue("sorted by score", picks.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertEquals(3, picks.first().enemies)
        val top = picks.take(5).joinToString { p -> "${roster.byId[p.heroId]?.name} %+.1f%% (beats ${p.beats}/3)".format(p.averageEdge * 100) }
        println("Vs Fanny+Ling+Miya -> $top")
        DraftAdvisor.teamPicksByLane(picks, roster).forEach { (lane, l) ->
            assertTrue(l.all { roster.byId[it.heroId]!!.laneEnums.contains(lane) && it.averageEdge > 0 })
        }
    }

    @Test fun reasonsComeFromOfficialTags() {
        val fanny = roster.heroes.first { it.name == "Fanny" }
        val matchups = DraftAdvisor.counterPicks(fanny, ApiParsers.parseMatchups(json, res("matchups_fanny.json")), roster)
        var withReason = 0
        matchups.take(10).forEach { m ->
            val pick = roster.byId[m.heroId] ?: return@forEach
            val r = DraftAdvisor.reasons(pick, fanny)
            if (r.isNotEmpty()) withReason++
            assertTrue(r.size <= 2)
            assertTrue(r.all { it.contains("Fanny") && it.first().isUpperCase() && !it.endsWith(".") })
            println("${pick.name} vs Fanny: ${pick.traits} -> $r")
        }
        assertTrue("most top counters get a reason", withReason >= 5)
    }

    @Test(expected = ApiFormatException::class) fun rejectsErrorEnvelope() {
        ApiParsers.parseHeroPage(json, """{"status":"error","code":"RESOURCE_NOT_FOUND","message":"Hero not found"}""")
    }
}
