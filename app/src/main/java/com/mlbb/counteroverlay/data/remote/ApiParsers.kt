package com.mlbb.counteroverlay.data.remote

import com.mlbb.counteroverlay.data.model.BuildUi
import com.mlbb.counteroverlay.data.model.CounterStat
import com.mlbb.counteroverlay.data.model.HeroUi
import com.mlbb.counteroverlay.data.model.ItemUi
import com.mlbb.counteroverlay.data.model.Lane
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Thrown when the API answers 200 but with an error envelope or an unexpected shape. */
class ApiFormatException(msg: String) : IllegalStateException(msg)

/**
 * Tree-walking parsers: every field is optional, so upstream additions or
 * missing fields never crash the overlay. Call from Dispatchers.Default.
 */
object ApiParsers {

    data class HeroPage(val heroes: List<HeroUi>, val total: Int)
    data class Rates(val winRate: Double?, val banRate: Double?, val pickRate: Double?)

    fun parseHeroPage(json: Json, raw: String): HeroPage {
        val data = envelope(json, raw)
        val heroes = data.records().mapNotNull { d ->
            val id = d.int("hero_id") ?: return@mapNotNull null
            val h = d.obj("hero")?.obj("data")
            val name = h?.str("name")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rel = d.obj("relation")
            HeroUi(
                id = id,
                name = name,
                roles = h.arr("sortid").titles("sort_title"),
                lanes = h.arr("roadsort").titles("road_sort_title").mapNotNull { Lane.from(it)?.label }.distinct(),
                weakAgainst = rel.ids("weak"),
                strongAgainst = rel.ids("strong"),
                synergy = rel.ids("assist"),
            )
        }
        return HeroPage(heroes, data.int("total") ?: heroes.size)
    }

    /** hero_id -> portrait URL, from GET heroes. */
    fun parseHeroIcons(json: Json, raw: String): Map<Int, String> =
        envelope(json, raw).records().mapNotNull { d ->
            val id = d.int("hero_id") ?: return@mapNotNull null
            val icon = d.obj("hero")?.obj("data")?.str("head") ?: return@mapNotNull null
            id to icon
        }.toMap()

    /** hero_id -> rates, from GET heroes/rank. */
    fun parseRates(json: Json, raw: String): Map<Int, Rates> =
        envelope(json, raw).records().mapNotNull { d ->
            val id = d.int("main_heroid") ?: return@mapNotNull null
            id to Rates(d.dbl("main_hero_win_rate"), d.dbl("main_hero_ban_rate"), d.dbl("main_hero_appearance_rate"))
        }.toMap()

    /** Official skill tags + specialities from GET heroes/{id}. */
    fun parseHeroTraits(json: Json, raw: String): List<String> {
        val h = envelope(json, raw).records().firstOrNull()?.obj("hero")?.obj("data") ?: return emptyList()
        val tags = h.arr("heroskilllist").orEmpty().flatMap { g ->
            g.obj()?.arr("skilllist").orEmpty().flatMap { sk ->
                sk.obj()?.arr("skilltag").orEmpty().mapNotNull { it.obj()?.str("tagname")?.trim() }
            }
        }
        val spec = h.arr("speciality").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
        return (tags + spec).filter { it.isNotBlank() }.distinct().sorted()
    }

    fun parseItems(json: Json, raw: String): Map<Int, ItemUi> =
        envelope(json, raw).records().mapNotNull { d ->
            val id = d.int("equipid") ?: return@mapNotNull null
            val name = d.str("equipname")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to ItemUi(id, name, d.str("equipicon"))
        }.toMap()

    /**
     * Full matchup table for the enemy hero, best counters first.
     *
     * IMPORTANT: upstream `increase_win_rate` is how much the MAIN hero's (the enemy's)
     * win rate goes UP against `heroid`. Positive values are heroes the enemy BEATS.
     * Verified on all 133 heroes: the table is antisymmetric (A vs B == -(B vs A)) and the
     * in-game guide's "weak against" heroes have negative values. So a counter's edge is
     * the negated value.
     */
    fun parseMatchups(json: Json, raw: String): List<CounterStat> {
        val first = envelope(json, raw).records().firstOrNull() ?: return emptyList()
        val self = first.int("main_heroid")
        return first.arr("sub_hero").orEmpty().mapNotNull { s ->
            val o = s.obj() ?: return@mapNotNull null
            val id = o.int("heroid")?.takeIf { it > 0 && it != self } ?: return@mapNotNull null
            val enemyIncrease = o.dbl("increase_win_rate") ?: return@mapNotNull null
            CounterStat(id, winRateEdge = -enemyIncrease, heroWinRate = o.dbl("hero_win_rate") ?: 0.0)
        }.distinctBy { it.heroId }.sortedByDescending { it.winRateEdge }
    }

    /** Builds sorted by popularity (most reliable data first). */
    fun parseBuilds(json: Json, raw: String): List<BuildUi> =
        envelope(json, raw).records().flatMap { rec ->
            rec.arr("build").orEmpty().mapNotNull { b ->
                val o = b.obj() ?: return@mapNotNull null
                val items = o.arr("equipid").orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                if (items.isEmpty()) return@mapNotNull null
                val emblem = o.obj("emblem")?.obj("data")
                val spell = o.obj("battleskill")?.obj("data")?.obj("__data")
                BuildUi(
                    itemIds = items,
                    emblem = emblem?.str("emblemname"),
                    emblemIcon = emblem?.str("attriicon"),
                    spell = spell?.str("skillname"),
                    spellIcon = spell?.str("skillicon"),
                    winRate = o.dbl("build_win_rate") ?: 0.0,
                    pickRate = o.dbl("build_pick_rate") ?: 0.0,
                )
            }
        }.distinctBy { listOf(it.itemIds, it.emblem, it.spell) }.sortedByDescending { it.pickRate }

    /** Validates `{"code":0,"data":{...}}` and returns `data`. */
    private fun envelope(json: Json, raw: String): JsonObject {
        val root = json.parseToJsonElement(raw).obj() ?: throw ApiFormatException("Not a JSON object")
        if (root.str("status") == "error") throw ApiFormatException(root.str("message") ?: "API error")
        val code = root.int("code")
        if (code != null && code != 0) throw ApiFormatException("API code $code: ${root.str("message")}")
        return root.obj("data") ?: throw ApiFormatException("Missing data")
    }

    // ---- tiny JSON tree helpers -------------------------------------------------
    private fun JsonObject.records(): List<JsonObject> =
        arr("records").orEmpty().mapNotNull { it.obj()?.obj("data") }

    private fun JsonElement.obj() = this as? JsonObject
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = this[k] as? JsonArray
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.dbl(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

    private fun JsonArray?.titles(key: String): List<String> = orEmpty().mapNotNull {
        it.obj()?.obj("data")?.str(key)?.trim()?.takeIf(String::isNotBlank)
            ?.replaceFirstChar(Char::uppercaseChar)
    }

    private fun JsonObject?.ids(key: String): List<Int> =
        this?.obj(key)?.arr("target_hero_id").orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.intOrNull }.filter { it > 0 }.distinct()
}
