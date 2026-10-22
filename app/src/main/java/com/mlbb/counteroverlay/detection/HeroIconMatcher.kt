package com.mlbb.counteroverlay.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.mlbb.counteroverlay.data.model.HeroUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Matches small icon crops from a screenshot against all known hero icons.
 *
 * Approach: normalised histogram correlation on the H and S channels of HSV.
 * This is fast (no ML dependency), rotation-invariant, and tolerant of minor
 * compression artefacts — hero icons in MLBB drafts have saturated, distinctive
 * colour palettes that separate well with this metric.
 *
 * For each comparison:
 *   1. Resize both images to [ICON_SIZE]×[ICON_SIZE].
 *   2. Build a joint H×S histogram (16×8 = 128 bins).
 *   3. Return the cosine similarity of the two histograms.
 *
 * Usage:
 *   val matcher = HeroIconMatcher(context, imageLoader)
 *   matcher.preload(heroList)         // warm the icon cache once
 *   val id = matcher.findBestMatch(cropBitmap, heroList)  // ≥ 0 or NO_MATCH
 */
class HeroIconMatcher(
    private val context: Context,
    private val imageLoader: ImageLoader,
) {

    /** Cached histograms for each hero id. */
    private val histCache = HashMap<Int, FloatArray>()

    /** Minimum similarity to count as a positive match (0–1). */
    private val threshold = THRESHOLD

    // ------------------------------------------------------------------ public API

    /**
     * Downloads and caches all hero icon histograms in parallel.
     * Call this once after the roster is loaded so [findBestMatch] is instant.
     */
    suspend fun preload(heroes: List<HeroUi>) = withContext(Dispatchers.IO) {
        heroes.map { hero ->
            async {
                if (hero.id !in histCache && hero.icon != null) {
                    loadHistogram(hero)?.let { histCache[hero.id] = it }
                }
            }
        }.awaitAll()
        Log.d(TAG, "Preloaded ${histCache.size}/${heroes.size} hero histograms")
    }

    /**
     * Returns the hero id whose icon best matches [crop], or [NO_MATCH] when
     * no hero exceeds the similarity threshold.
     *
     * @param crop      A small region of a screenshot (any size — will be scaled internally).
     * @param heroes    The roster to search within (typically the full list).
     */
    suspend fun findBestMatch(crop: Bitmap, heroes: List<HeroUi>): Int =
        withContext(Dispatchers.Default) {
            val cropHist = histogramOf(crop)
            var bestId = NO_MATCH
            var bestScore = threshold
            for (hero in heroes) {
                val ref = histCache[hero.id]
                    ?: (if (hero.icon != null) loadHistogram(hero)?.also { histCache[hero.id] = it } else null)
                    ?: continue
                val score = cosineSimilarity(cropHist, ref)
                if (score > bestScore) { bestScore = score; bestId = hero.id }
            }
            if (bestId != NO_MATCH) Log.d(TAG, "Best match id=$bestId score=%.3f".format(bestScore))
            bestId
        }

    /**
     * Returns up to [topK] (heroId, score) pairs sorted by similarity descending.
     * Useful for debugging or showing "possible matches".
     */
    suspend fun topMatches(crop: Bitmap, heroes: List<HeroUi>, topK: Int = 5): List<Pair<Int, Float>> =
        withContext(Dispatchers.Default) {
            val cropHist = histogramOf(crop)
            heroes.mapNotNull { hero ->
                val ref = histCache[hero.id] ?: return@mapNotNull null
                Pair(hero.id, cosineSimilarity(cropHist, ref))
            }.sortedByDescending { it.second }.take(topK)
        }

    // ------------------------------------------------------------------ internals

    private suspend fun loadHistogram(hero: HeroUi): FloatArray? {
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
                bmp?.let { histogramOf(it) }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load icon for hero ${hero.id}: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ histogram math

    /**
     * Builds a normalised H×S histogram (16 hue bins × 8 sat bins = 128 values).
     * Pixels with saturation < 0.15 are skipped (achromatic — grey/white/black
     * backgrounds would otherwise dominate and give false positives).
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
        if (count == 0) return hist // all transparent / achromatic — valid but will not match
        val norm = 1f / count
        for (i in hist.indices) hist[i] *= norm
        return hist
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var magA = 0f; var magB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; magA += a[i] * a[i]; magB += b[i] * b[i] }
        val denom = Math.sqrt((magA * magB).toDouble()).toFloat()
        return if (denom < 1e-9f) 0f else dot / denom
    }

    companion object {
        const val NO_MATCH = -1

        private const val TAG = "HeroIconMatcher"
        private const val ICON_SIZE = 64       // px — small enough for fast histogram
        private const val HUE_BINS = 16
        private const val SAT_BINS = 8
        private const val MIN_SATURATION = 0.15f
        private const val THRESHOLD = 0.75f   // tune: raise to reduce false positives
    }
}
