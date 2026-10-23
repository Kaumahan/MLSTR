package com.mlbb.counteroverlay.detection

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        val scores: List<Float> = emptyList(),   // best similarity per slot (diagnostics)
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

        val rects = slotRects(w, h)
        val matches = rects.map { rect ->
            async {
                val crop = cropSafely(screenshot, rect)
                if (crop != null) {
                    val (id, score) = iconMatcher.bestMatch(crop, heroes)
                    crop.recycle()
                    id to score
                } else HeroIconMatcher.NO_MATCH to 0f
            }
        }.awaitAll()

        val threshold = iconMatcher.matchThreshold
        val results = matches.map { (id, score) ->
            id.takeIf { it != HeroIconMatcher.NO_MATCH && score > threshold }
        }
        val scores = matches.map { it.second }

        Log.d(TAG, "Detection ${screenshot.width}x${screenshot.height}: " +
            matches.joinToString { (id, s) -> "id=$id score=%.2f".format(s) })
        if (SAVE_DEBUG_IMAGES) saveDebugImages(screenshot, rects, matches)
        DetectionResult(slots = results, screenshot = screenshot, scores = scores)
    }

    /**
     * Saves the captured screen with the 5 crop rectangles drawn on it (and the best guess +
     * score for each) to Pictures/AceCounterDebug, so you can SEE where the app is looking.
     * Open it in your gallery and share it with the developer. Set [SAVE_DEBUG_IMAGES] to false
     * before releasing.
     */
    private fun saveDebugImages(shot: Bitmap, rects: List<RectF>, matches: List<Pair<Int, Float>>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val annotated = shot.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(annotated)
            val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.RED }
            val text = Paint().apply { color = Color.YELLOW; textSize = annotated.height / 28f; isFakeBoldText = true }
            rects.forEachIndexed { i, r ->
                canvas.drawRect(r, stroke)
                val (id, s) = matches[i]
                canvas.drawText("#$i id=$id %.2f".format(s), r.left, (r.top - 8f).coerceAtLeast(text.textSize), text)
            }
            val stamp = System.currentTimeMillis()
            save(annotated, "ace_debug_${stamp}_annotated.png")
            save(shot, "ace_debug_${stamp}_raw.png")
            annotated.recycle()
        }.onFailure { Log.w(TAG, "Could not save debug images: ${it.message}") }
    }

    private fun save(bmp: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AceCounterDebug")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Pixel rectangles of the 5 enemy portraits.
     *
     * Landscape (how Mobile Legends is really played): the enemy team is a column of 5 portraits
     * on the RIGHT edge, labelled "Player 1..5". The game UI scales with screen HEIGHT and is
     * anchored to the right edge, so positions are measured from the right edge in units of
     * height. This keeps them correct on phones with different aspect ratios (18:9, 20:9, ...).
     * Measured on a real 843x379 draft screenshot.
     */
    private fun slotRects(w: Float, h: Float): List<RectF> {
        if (w <= h) return ENEMY_SLOTS.map { scaledRect(it, w, h) }   // legacy portrait guess
        val left = w - LAND_LEFT_FROM_RIGHT * h
        val right = w - LAND_RIGHT_FROM_RIGHT * h
        return List(5) { i ->
            val top = (LAND_FIRST_TOP + LAND_PITCH * i) * h
            RectF(left, top, right, top + LAND_SLOT_H * h)
        }
    }

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

        // Landscape enemy column, all in units of screen height (H), x measured from the right edge.
        private const val LAND_LEFT_FROM_RIGHT = 0.272f    // portrait left edge  = W - 0.272 H
        private const val LAND_RIGHT_FROM_RIGHT = 0.108f   // portrait right edge = W - 0.108 H
        private const val LAND_FIRST_TOP = 46f / 379f      // top of "Player 1" portrait
        private const val LAND_PITCH = 60f / 379f          // distance between portraits
        private const val LAND_SLOT_H = 46f / 379f         // portrait height (excludes name label)

        /** Writes screenshots to Pictures/AceCounterDebug on every Detect. Turn OFF for release. */
        private const val SAVE_DEBUG_IMAGES = true

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
