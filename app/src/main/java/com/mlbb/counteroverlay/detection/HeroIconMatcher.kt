package com.mlbb.counteroverlay.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.mlbb.counteroverlay.data.model.HeroUi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Matches small portrait crops from a screenshot against all known hero icons.
 *
 * Every image (crop or icon) is turned into four features:
 *  1. COLOUR MIX  - joint hue x saturation histogram (what colours, not where).
 *  2. COLOUR MAP  - 8x8 grid of average RGB (WHERE the colours are: hair on top, coat below...).
 *  3. SHAPE       - 20x20 brightness pattern (light/dark layout, survives skin recolours).
 *  4. EDGES       - gradient-orientation histogram (HOG-like, 4x4 cells x 8 directions). This is
 *                   the most detailed feature: it captures the outlines of face, hair, weapons and
 *                   armour, so heroes that merely share a palette (Hanabi / Granger / Clint,
 *                   Masha / Nolan ...) are told apart. It is also unaffected by the draft screen
 *                   dimming the portraits.
 *
 * Maps 2-4 are compared as correlations (mean-removed), so overall brightness and contrast
 * differences do not matter. The final score is a weighted blend of all four.
 *
 * The matcher only SCORES. Deciding which hero goes in which slot (each hero at most once) is done
 * by [DraftScreenDetector], which needs the full ranking of every slot.
 *
 * Usage:
 *   matcher.preload(heroList)                          // warm the icon cache once
 *   val top = matcher.rank(listOf(crop), heroList, 8)  // best first
 */
class HeroIconMatcher(
    private val context: Context,
    private val imageLoader: ImageLoader,
) {

    /** Features of one image. */
    private class Feature(
        val hist: FloatArray,
        val grid: FloatArray,
        val gray: FloatArray,
        val edge: FloatArray,
    )

    /** One ranked guess. [score] is the blended similarity (0-1) used for ranking AND accepting. */
    data class Candidate(
        val heroId: Int,
        val score: Float,   // blended ranking score
        val hist: Float,    // colour mix similarity
        val grid: Float,    // colour map correlation
        val gray: Float,    // shape correlation
        val edge: Float,    // outline (edge direction) correlation
    )

    private val cache = ConcurrentHashMap<Int, Feature>()

    /** Minimum blended score of the best hero for a slot to count as a match (0-1). */
    val matchThreshold: Float get() = THRESHOLD

    // ------------------------------------------------------------------ public API

    /**
     * Downloads and caches all hero icon features in parallel.
     * Call this once after the roster is loaded so matching is instant.
     */
    suspend fun preload(heroes: List<HeroUi>) = withContext(Dispatchers.IO) {
        heroes.map { hero ->
            async {
                if (!cache.containsKey(hero.id) && hero.icon != null) {
                    loadFeature(hero)?.let { cache[hero.id] = it }
                }
            }
        }.awaitAll()
        Log.d(TAG, "Preloaded ${cache.size}/${heroes.size} hero icons")
    }

    /**
     * Hero ids ranked by blended similarity, best first (at most [topK]).
     *
     * [crops] are slightly different cuts of the SAME portrait (the exact crop, a bit larger, a bit
     * smaller, shifted...). Each hero keeps its best score over all cuts, so a few pixels of
     * misalignment no longer ruin the match. The caller recycles the crops.
     */
    suspend fun rank(crops: List<Bitmap>, heroes: List<HeroUi>, topK: Int = 8): List<Candidate> =
        withContext(Dispatchers.Default) {
            val queries = crops.map { featureOf(it) }
            val out = ArrayList<Candidate>(heroes.size)
            for (hero in heroes) {
                val ref = cache[hero.id]
                    ?: (if (hero.icon != null) loadFeature(hero)?.also { cache[hero.id] = it } else null)
                    ?: continue
                var best: Candidate? = null
                for (q in queries) {
                    val c = compare(hero.id, q, ref)
                    if (best == null || c.score > best.score) best = c
                }
                best?.let { out += it }
            }
            out.sortByDescending { it.score }
            val top = out.take(topK)
            Log.d(TAG, "Top: " + top.take(3).joinToString {
                "id=${it.heroId} s=%.2f h=%.2f g=%.2f y=%.2f e=%.2f".format(it.score, it.hist, it.grid, it.gray, it.edge)
            })
            top
        }

    /** Single-crop convenience. */
    suspend fun rank(crop: Bitmap, heroes: List<HeroUi>, topK: Int = 8): List<Candidate> =
        rank(listOf(crop), heroes, topK)

    private fun compare(heroId: Int, q: Feature, ref: Feature): Candidate {
        val h = dot(q.hist, ref.hist, normalise = true)
        val g = dot(q.grid, ref.grid)
        val s = dot(q.gray, ref.gray)
        val e = dot(q.edge, ref.edge)
        val blended = W_HIST * h +
            W_GRID * g.coerceAtLeast(0f) +
            W_GRAY * s.coerceAtLeast(0f) +
            W_EDGE * e.coerceAtLeast(0f)
        return Candidate(heroId, blended, h, g, s, e)
    }

    // ------------------------------------------------------------------ loading

    private suspend fun loadFeature(hero: HeroUi): Feature? {
        val url = hero.icon ?: return null
        return try {
            val req = ImageRequest.Builder(context)
                .data(url)
                .size(ICON_SIZE, ICON_SIZE)
                .allowHardware(false) // need to read pixels
                .build()
            val result = imageLoader.execute(req)
            if (result is SuccessResult) {
                val bmp = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                bmp?.let { featureOf(it) }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load icon for hero ${hero.id}: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ features

    private fun featureOf(src: Bitmap): Feature {
        val hist = histogramOf(src)

        val s = Bitmap.createScaledBitmap(src, FEAT, FEAT, true)
        val px = IntArray(FEAT * FEAT)
        s.getPixels(px, 0, FEAT, 0, 0, FEAT, FEAT)
        if (s !== src) s.recycle()

        // Ignore a thin border: draft portraits fade/glow at the edges, icons do not.
        val region = FEAT - 2 * MARGIN

        // Luminance of every pixel (used by the shape and edge features).
        val lum = FloatArray(FEAT * FEAT)
        for (i in px.indices) {
            val p = px[i]
            lum[i] = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
        }

        // COLOUR MAP: GRID x GRID cells of average R,G,B, per channel mean-removed, unit length.
        val grid = FloatArray(GRID * GRID * 3)
        for (cy in 0 until GRID) for (cx in 0 until GRID) {
            val xa = MARGIN + cx * region / GRID; val xb = MARGIN + (cx + 1) * region / GRID
            val ya = MARGIN + cy * region / GRID; val yb = MARGIN + (cy + 1) * region / GRID
            var r = 0f; var g = 0f; var b = 0f; var n = 0
            for (y in ya until yb) for (x in xa until xb) {
                val p = px[y * FEAT + x]
                r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
            }
            val i = (cy * GRID + cx) * 3
            if (n > 0) { grid[i] = r / n / 255f; grid[i + 1] = g / n / 255f; grid[i + 2] = b / n / 255f }
        }
        for (ch in 0..2) {
            var mean = 0f
            for (c in 0 until GRID * GRID) mean += grid[c * 3 + ch]
            mean /= (GRID * GRID)
            for (c in 0 until GRID * GRID) grid[c * 3 + ch] -= mean
        }
        unitLength(grid)

        // SHAPE: brightness pooled 2x2 -> (region/2)^2 values, mean-removed, unit length.
        val side = region / 2
        val gray = FloatArray(side * side)
        for (y in 0 until side) for (x in 0 until side) {
            var sum = 0f
            for (dy in 0..1) for (dx in 0..1) sum += lum[(MARGIN + y * 2 + dy) * FEAT + (MARGIN + x * 2 + dx)]
            gray[y * side + x] = sum / 4f
        }
        meanRemoveAndNormalise(gray)

        // EDGES: gradient direction histogram per cell, weighted by edge strength.
        val edge = FloatArray(HOG_CELLS * HOG_CELLS * HOG_BINS)
        val lo = MARGIN + 1
        val hi = FEAT - MARGIN - 1
        for (y in lo until hi) for (x in lo until hi) {
            val gx = lum[y * FEAT + x + 1] - lum[y * FEAT + x - 1]
            val gy = lum[(y + 1) * FEAT + x] - lum[(y - 1) * FEAT + x]
            val mag = Math.sqrt((gx * gx + gy * gy).toDouble()).toFloat()
            if (mag < 1e-4f) continue
            var ang = Math.atan2(gy.toDouble(), gx.toDouble())          // -pi..pi
            if (ang < 0) ang += Math.PI                                  // unsigned direction 0..pi
            val bin = ((ang / Math.PI) * HOG_BINS).toInt().coerceIn(0, HOG_BINS - 1)
            val cx = ((x - MARGIN) * HOG_CELLS / region).coerceIn(0, HOG_CELLS - 1)
            val cy = ((y - MARGIN) * HOG_CELLS / region).coerceIn(0, HOG_CELLS - 1)
            edge[(cy * HOG_CELLS + cx) * HOG_BINS + bin] += mag
        }
        meanRemoveAndNormalise(edge)

        return Feature(hist, grid, gray, edge)
    }

    private fun meanRemoveAndNormalise(v: FloatArray) {
        var m = 0f
        for (x in v) m += x
        m /= v.size
        for (i in v.indices) v[i] -= m
        unitLength(v)
    }

    private fun unitLength(v: FloatArray) {
        var m = 0f
        for (x in v) m += x * x
        val norm = Math.sqrt(m.toDouble()).toFloat()
        if (norm > 1e-6f) for (i in v.indices) v[i] /= norm
    }

    /** Dot product. With [normalise] it is a cosine similarity; otherwise inputs are unit length. */
    private fun dot(a: FloatArray, b: FloatArray, normalise: Boolean = false): Float {
        var d = 0f; var ma = 0f; var mb = 0f
        for (i in a.indices) {
            d += a[i] * b[i]
            if (normalise) { ma += a[i] * a[i]; mb += b[i] * b[i] }
        }
        if (!normalise) return d
        val denom = Math.sqrt((ma * mb).toDouble()).toFloat()
        return if (denom < 1e-9f) 0f else d / denom
    }

    /**
     * Builds a normalised H x S histogram (16 hue bins x 8 sat bins = 128 values).
     * Pixels with saturation < 0.15 are skipped (achromatic: grey/white/black backgrounds
     * would otherwise dominate).
     */
    private fun histogramOf(src: Bitmap): FloatArray {
        val scaled = if (src.width == ICON_SIZE && src.height == ICON_SIZE) src
        else Bitmap.createScaledBitmap(src, ICON_SIZE, ICON_SIZE, true)

        val hist = FloatArray(HUE_BINS * SAT_BINS)
        val hsv = FloatArray(3)
        var count = 0

        for (y in 0 until ICON_SIZE) {
            for (x in 0 until ICON_SIZE) {
                val pixel = scaled.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                if (alpha < 128) continue // skip transparent pixels
                Color.colorToHSV(pixel, hsv)
                val s = hsv[1]
                if (s < MIN_SATURATION) continue // skip achromatic
                val hBin = ((hsv[0] / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
                val sBin = (s * SAT_BINS).toInt().coerceIn(0, SAT_BINS - 1)
                hist[hBin * SAT_BINS + sBin]++
                count++
            }
        }

        if (scaled !== src) scaled.recycle()
        if (count == 0) return hist // all transparent / achromatic: valid but will not match
        val norm = 1f / count
        for (i in hist.indices) hist[i] *= norm
        return hist
    }

    companion object {
        const val NO_MATCH = -1

        private const val TAG = "HeroIconMatcher"
        private const val ICON_SIZE = 64       // px: size for the colour-mix histogram
        private const val HUE_BINS = 16
        private const val SAT_BINS = 8
        private const val MIN_SATURATION = 0.15f

        /**
         * Accept threshold on the BLENDED score (the old 0.75 was on colour mix alone, which is why
         * look-alike palettes got through). Correlation features score roughly 0.2-0.4 for a wrong
         * hero and 0.6+ for the right one. Raise it if wrong heroes still show up, lower it if real
         * picks are rejected; the debug screenshot prints the score of every guess.
         */
        private const val THRESHOLD = 0.50f

        private const val FEAT = 48            // px: size for the colour map, shape and edge features
        private const val MARGIN = 4           // px border ignored on each side
        private const val GRID = 8             // colour map is GRID x GRID cells
        private const val HOG_CELLS = 4        // edge map is HOG_CELLS x HOG_CELLS cells...
        private const val HOG_BINS = 8         // ...with HOG_BINS directions each

        // Ranking blend. Outline detail carries the most weight because it separates heroes that
        // share a colour palette; colour mix is only a small hint now.
        private const val W_HIST = 0.10f
        private const val W_GRID = 0.25f
        private const val W_GRAY = 0.25f
        private const val W_EDGE = 0.40f
    }
}
