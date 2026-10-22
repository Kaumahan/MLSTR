package com.mlbb.counteroverlay.data

import com.mlbb.counteroverlay.data.model.HeroUi
import kotlin.math.min

/**
 * Typo-tolerant hero search. Finds "Lukas" for "lucas", "Fanny" for "fany",
 * "Granger" for "granjer", "X.Borg" for "xborg", and filters by role or lane
 * ("mage", "gold", "roam"). Fast enough to run on every keystroke.
 */
object HeroSearch {

    /** Community nicknames -> official names. */
    private val aliases = mapOf(
        "yz" to "Yu Zhong", "yss" to "Yi Sun-shin", "lapu" to "Lapu-Lapu",
        "xb" to "X.Borg", "lolo" to "Lolita", "popol" to "Popol and Kupa",
        "kupa" to "Popol and Kupa", "lylia" to "Lylia", "change" to "Chang'e",
        "ml" to "", // ignore
    )

    fun search(heroes: List<HeroUi>, query: String): List<HeroUi> {
        val q = normalize(query)
        if (q.isEmpty()) return heroes
        val alias = aliases[q]?.let(::normalize)?.takeIf { it.isNotEmpty() }
        return heroes
            .map { it to score(it, q, alias) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<HeroUi, Int>> { it.second }.thenBy { it.first.name })
            .map { it.first }
    }

    private fun score(hero: HeroUi, q: String, alias: String?): Int {
        val name = normalize(hero.name)
        if (alias != null && name == alias) return 120
        if (name == q) return 110
        if (name.startsWith(q)) return 100
        // Any word of a multi-word name ("Sun" in "Yi Sun-shin").
        if (hero.name.split(' ', '-', '.', '\'').any { normalize(it).startsWith(q) }) return 90
        if (q.length >= 3 && q in name) return 80
        if (hero.roles.any { normalize(it).startsWith(q) } ||
            hero.lanes.any { normalize(it).startsWith(q) }) return 60
        if (q.length < 3) return 0

        // Typo tolerance: compare against the whole name and against a same-length prefix.
        val allowed = if (q.length <= 4) 1 else 2
        val dist = min(distance(q, name), distance(q, name.take(q.length)))
        if (dist <= allowed) return 50 - dist * 10
        if (isSubsequence(q, name) && q.length >= 4) return 20
        return 0
    }

    fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun isSubsequence(q: String, s: String): Boolean {
        var i = 0
        for (c in s) if (i < q.length && q[i] == c) i++
        return i == q.length
    }

    /** Damerau-Levenshtein (optimal string alignment): swaps like "fnany" count as one typo. */
    fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
        return d[a.length][b.length]
    }
}
