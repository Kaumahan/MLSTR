package com.mlbb.counteroverlay.data.model

import kotlinx.serialization.Serializable

/** In-game lanes, in the order the overlay shows them. `apiKey` is the value the API expects. */
enum class Lane(val label: String, val apiKey: String) {
    EXP("EXP", "exp"),
    JUNGLE("Jungle", "jungle"),
    MID("Mid", "mid"),
    GOLD("Gold", "gold"),
    ROAM("Roam", "roam");

    companion object {
        /** Accepts "Exp Lane", "EXP", "gold lane", "Roam", ... */
        fun from(text: String?): Lane? {
            val key = text?.lowercase()?.replace("lane", "")?.trim() ?: return null
            return entries.firstOrNull { it.apiKey == key || it.label.lowercase() == key }
        }
    }
}

/** Ranked tier filter. `apiKey` is the value the API expects. */
enum class Rank(val label: String, val apiKey: String) {
    ALL("All ranks", "all"),
    EPIC("Epic", "epic"),
    LEGEND("Legend", "legend"),
    MYTHIC("Mythic", "mythic"),
    HONOR("Mythical Honor", "honor"),
    GLORY("Mythical Glory", "glory");

    val shortLabel: String get() = when (this) { ALL -> "All"; HONOR -> "Honor"; GLORY -> "Glory"; else -> label }

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.apiKey == key } ?: ALL
    }
}

@Serializable
data class RateUi(val winRate: Double? = null, val banRate: Double? = null, val pickRate: Double? = null)

/**
 * Compact hero model. It is also the on-disk cache format, so the overlay never
 * has to re-parse the large raw API responses.
 */
@Serializable
data class HeroUi(
    val id: Int,
    val name: String,
    val icon: String? = null,
    val roles: List<String> = emptyList(),
    val lanes: List<String> = emptyList(),
    /** Official in-game relation: heroes this hero is weak against. */
    val weakAgainst: List<Int> = emptyList(),
    val strongAgainst: List<Int> = emptyList(),
    val synergy: List<Int> = emptyList(),
    /** Ranked stats, last 7 days. Null when unavailable. */
    val winRate: Double? = null,
    val banRate: Double? = null,
    val pickRate: Double? = null,
    /** Official skill tags + specialities, e.g. "CC", "Mobility", "Shield", "Burst", "Poke". */
    val traits: List<String> = emptyList(),
) {
    val laneEnums: List<Lane> get() = lanes.mapNotNull { Lane.from(it) }

    val subtitle: String
        get() = listOf(roles.joinToString(" / "), lanes.joinToString(" / ") { "$it Lane" })
            .filter { it.isNotBlank() }.joinToString("  ·  ")
}

@Serializable
data class ItemUi(val id: Int, val name: String, val icon: String? = null)

/** Everything the overlay needs to open instantly. Cached as one file. */
@Serializable
data class Roster(
    val heroes: List<HeroUi> = emptyList(),
    val items: Map<Int, ItemUi> = emptyMap(),
) {
    @kotlinx.serialization.Transient
    val byId: Map<Int, HeroUi> = heroes.associateBy { it.id }
}

/**
 * One matchup against the selected enemy.
 * [winRateEdge] = how many points the ENEMY's win rate drops when facing [heroId]
 * (0.035 = the enemy wins 3.5% less often). Positive = good counter pick.
 * [heroWinRate] is [heroId]'s overall win rate, for context only.
 */
@Serializable
data class CounterStat(
    val heroId: Int,
    val winRateEdge: Double,
    val heroWinRate: Double = 0.0,
)

@Serializable
data class BuildUi(
    val itemIds: List<Int>,
    val emblem: String? = null,
    val emblemIcon: String? = null,
    val spell: String? = null,
    val spellIcon: String? = null,
    val winRate: Double = 0.0,
    val pickRate: Double = 0.0,
)

enum class DataSource { NETWORK, DISK_CACHE, BUNDLED }

/** Generic stale-while-revalidate state for any cached resource. */
data class Loadable<T>(
    val value: T? = null,
    val source: DataSource? = null,
    val fetchedAtMillis: Long = 0L,
    val isLoading: Boolean = false,
    val error: String? = null,
)
