package com.mlbb.counteroverlay.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.mlbb.counteroverlay.data.model.HeroUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Orchestrates a single "detect heroes on screen" pass:
 *   1. Capture a frame via [ScreenCaptureManager].
 *   2. Crop each of the up to 5 enemy pick slots using layout constants.
 *   3. Match each crop against the roster using [HeroIconMatcher].
 *   4. Return the detected hero IDs (up to 5, unknowns omitted).
 *
 * ----- MLBB Draft-Pick Layout (portrait, 1080 px wide reference) ------
 *
 *  The enemy team's 5 hero picks appear in a horizontal row near the top
 *  of the draft screen.  On the official 1080 × 1920 reference layout:
 *
 *   Slot 0 (left)  : x ≈  60, y ≈ 140,  w ≈ 130, h ≈ 130
 *   Slot 1          : x ≈ 220, y ≈ 140,  w ≈ 130, h ≈ 130
 *   Slot 2 (centre): x ≈ 475, y ≈  90,  w ≈ 130, h ≈ 130
 *   Slot 3          : x ≈ 730, y ≈ 140,  w ≈ 130, h ≈ 130
 *   Slot 4 (right)  : x ≈ 890, y ≈ 140,  w ≈ 130, h ≈ 130
 *
 *  All values are expressed as **fractions of the screen dimensions** so
 *  they scale to any resolution (720p → 4K).  Adjust [ENEMY_SLOTS] if
 *  Moonton changes the layout in a game update.
 *
 * ----- Landscape note -----------------------------------------------
 *  MLBB runs in landscape only on tablets.  [scaledRect] handles both
 *  orientations by using width for x/w and height for y/h always.
 */
class DraftScreenDetector(
    private val context: Context,
    private val screenCapture: ScreenCaptureManager,
    private val iconMatcher: HeroIconMatcher,
) {

    data class DetectionResult(
        /** Non-null entries are hero IDs whose icon was recognised. */
        val slots: List<Int?>,          // length 5; null = empty / unknown slot
        val screenshot: Bitmap,         // caller must recycle when done
    ) {
        val detected: List<Int> get() = slots.filterNotNull()
    }

    /**
     * Capture the screen right now and match hero icons.
     *
     * @param heroes The full roster to match against.
     * @throws IllegalStateException if [ScreenCaptureManager] is not running.
     */
    suspend fun detect(heroes: List<HeroUi>): DetectionResult = withContext(Dispatchers.Default) {
        // Capture from main thread requirement is handled by ScreenCaptureManager internally.
        val screenshot = withContext(Dispatchers.Main) { screenCapture.captureFrame() }

        val w = screenshot.width.toFloat()
        val h = screenshot.height.toFloat()

        val results = ENEMY_SLOTS.map { slot ->
            async {
                val rect = scaledRect(slot, w, h)
                val crop = cropSafely(screenshot, rect)
                if (crop != null) {
                    val id = iconMatcher.findBestMatch(crop, heroes)
                    crop.recycle()
                    id.takeIf { it != HeroIconMatcher.NO_MATCH }
                } else null
            }
        }.awaitAll()

        Log.d(TAG, "Detection: ${results.map { it ?: '-' }}")
        DetectionResult(slots = results, screenshot = screenshot)
    }

    // ------------------------------------------------------------------ helpers

    /** Scale a normalised [SlotDef] to pixel coordinates for [screen] dimensions. */
    private fun scaledRect(slot: SlotDef, screenW: Float, screenH: Float): RectF {
        return RectF(
            slot.xFrac * screenW,
            slot.yFrac * screenH,
            (slot.xFrac + slot.wFrac) * screenW,
            (slot.yFrac + slot.hFrac) * screenH,
        )
    }

    private fun cropSafely(src: Bitmap, rect: RectF): Bitmap? {
        val l = rect.left.toInt().coerceAtLeast(0)
        val t = rect.top.toInt().coerceAtLeast(0)
        val r = rect.right.toInt().coerceAtMost(src.width)
        val b = rect.bottom.toInt().coerceAtMost(src.height)
        val w = r - l; val h = b - t
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(src, l, t, w, h)
    }

    // ------------------------------------------------------------------ layout

    /** Normalised (0–1) slot rectangle on the enemy team's pick row. */
    private data class SlotDef(
        val xFrac: Float, val yFrac: Float,
        val wFrac: Float, val hFrac: Float,
    )

    companion object {
        private const val TAG = "DraftScreenDetector"

        // ---------------------------------------------------------------
        // MLBB draft pick layout — portrait 1080 × 1920 reference coords.
        // Expressed as fractions (value / 1080 or value / 1920).
        //
        //  The enemy row (top half of the screen, opponent = upper half):
        //    Slots 0–4 left→right, y top = 140/1920, icon height ≈ 130/1920
        //
        // ⚠  These are approximate.  If the app auto-detects wrong heroes,
        //    capture a screenshot and measure the actual pick positions —
        //    the values below are a good starting point for 1080p devices.
        // ---------------------------------------------------------------
        private val ENEMY_SLOTS = listOf(
            // xFrac        yFrac        wFrac        hFrac
            SlotDef(60f/1080,  140f/1920, 130f/1080, 130f/1920),   // slot 0
            SlotDef(220f/1080, 140f/1920, 130f/1080, 130f/1920),   // slot 1
            SlotDef(475f/1080,  90f/1920, 130f/1080, 130f/1920),   // slot 2 (centre / ban area)
            SlotDef(730f/1080, 140f/1920, 130f/1080, 130f/1920),   // slot 3
            SlotDef(890f/1080, 140f/1920, 130f/1080, 130f/1920),   // slot 4
        )
    }
}
