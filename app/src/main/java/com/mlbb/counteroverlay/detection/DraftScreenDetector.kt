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
 *   4. Assign heroes to slots so no hero appears twice, and return the IDs (unknowns omitted).
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
        val debugNote: String? = null,           // outcome of saving the debug images
        /** The portrait crop of each slot (null = off-screen). Kept so the user can teach the app. */
        val thumbs: List<Bitmap?> = emptyList(),
        /** Best guess per slot even when it was too weak to accept (shown as "Name?"). */
        val guesses: List<Int?> = emptyList(),
    ) {
        val detected: List<Int> get() = slots.filterNotNull()
    }

    /**
     * Capture the screen right now and match hero icons.
     *
     * @param heroes The full roster to match against.
     * @throws IllegalStateException if [ScreenCaptureManager] is not running.
     */
    suspend fun detect(heroes: List<HeroUi>): DetectionResult {
        // Capture from main thread requirement is handled by ScreenCaptureManager internally.
        val screenshot = withContext(Dispatchers.Main) { screenCapture.captureFrame() }
        return detectBitmap(screenshot, heroes)
    }

    /**
     * Same analysis on any full-screen screenshot (used by the "test with a picture" feature,
     * which needs no screen-capture permission). The picture must be the whole game screen.
     */
    suspend fun detectBitmap(screenshot: Bitmap, heroes: List<HeroUi>): DetectionResult = withContext(Dispatchers.Default) {
        val w = screenshot.width.toFloat()
        val h = screenshot.height.toFloat()

        val rects = slotRects(w, h)
        // Per slot: the best-ranked heroes (best first). Each slot is scored on several slightly
        // different cuts of its portrait so a few pixels of misalignment do not matter.
        val perSlot: List<Pair<List<HeroIconMatcher.Candidate>, Bitmap?>> = rects.map { rect ->
            async {
                val crops = cropVariants(screenshot, rect)
                if (crops.isNotEmpty()) {
                    val top = iconMatcher.rank(crops, heroes, CANDIDATES_PER_SLOT)
                    crops.drop(1).forEach { it.recycle() }
                    top to (crops[0] as Bitmap?)      // the exact crop is kept for teaching
                } else (emptyList<HeroIconMatcher.Candidate>() to (null as Bitmap?))
            }
        }.awaitAll()
        val ranked = perSlot.map { it.first }
        val thumbs = perSlot.map { it.second }

        // Pick one hero per slot such that NO hero is used twice and the total score is highest.
        val threshold = iconMatcher.matchThreshold
        val results = assignUnique(ranked, threshold, iconMatcher.learnedThreshold)
        val scores = ranked.map { it.firstOrNull()?.score ?: 0f }

        Log.d(TAG, "Detection ${screenshot.width}x${screenshot.height}: " +
            ranked.joinToString { top -> top.firstOrNull()?.let { "id=${it.heroId} s=%.2f".format(it.score) } ?: "-" } +
            "  ->  " + results.joinToString { it?.toString() ?: "-" })
        val note = if (SAVE_DEBUG_IMAGES) saveDebugImages(screenshot, rects, ranked, results, heroes) else null
        DetectionResult(slots = results, screenshot = screenshot, scores = scores, debugNote = note, thumbs = thumbs,
            guesses = ranked.map { it.firstOrNull()?.heroId })
    }

    /**
     * Saves the captured screen with the 5 crop rectangles drawn on it (and the best guess +
     * score for each) to Pictures/AceCounterDebug, so you can SEE where the app is looking.
     * Open it in your gallery and share it with the developer. Set [SAVE_DEBUG_IMAGES] to false
     * before releasing.
     */
    private fun saveDebugImages(
        shot: Bitmap,
        rects: List<RectF>,
        ranked: List<List<HeroIconMatcher.Candidate>>,
        results: List<Int?>,
        heroes: List<HeroUi>,
    ): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Debug images need Android 10+"
        return runCatching {
            val annotated = shot.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(annotated)
            val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.RED }
            val text = Paint().apply {
                color = Color.YELLOW; textSize = annotated.height / 38f
                isFakeBoldText = true; textAlign = Paint.Align.RIGHT
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            }
            val names = heroes.associate { it.id to it.name }
            rects.forEachIndexed { i, r ->
                canvas.drawRect(r, stroke)
                // Text sits to the LEFT of each box: the final verdict for the slot, then the top 3
                // guesses with blended score (s), colour (h) and outline (e) similarity.
                val top = ranked.getOrElse(i) { emptyList() }
                val x = r.left - 10f
                var y = r.top + text.textSize
                val picked = results.getOrNull(i)
                canvas.drawText("#$i " + (picked?.let { "-> ${names[it] ?: it}" } ?: "REJECTED"), x, y, text)
                top.take(3).forEachIndexed { k, c ->
                    y += text.textSize * 1.15f
                    canvas.drawText("${k + 1}. ${names[c.heroId] ?: c.heroId}  s%.2f h%.2f e%.2f".format(c.score, c.hist, c.edge), x, y, text)
                }
            }
            val stamp = System.currentTimeMillis()
            save(annotated, "ace_debug_${stamp}_annotated.png")
            save(shot, "ace_debug_${stamp}_raw.png")
            annotated.recycle()
            "Debug images saved in Gallery > Pictures > AceCounterDebug"
        }.getOrElse {
            Log.w(TAG, "Could not save debug images: ${it.message}")
            "Debug images NOT saved: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun save(bmp: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AceCounterDebug")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Android refused to create the file")
        val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("Could not open the file")
        out.use { check(bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG write failed" } }
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

    /**
     * Chooses one hero per slot with the rule "a hero can only be on the enemy team once".
     *
     * A slot may only take a candidate whose score reaches [threshold]; otherwise it stays empty.
     * Among all valid combinations the one with the highest total score wins, so when two slots
     * both think "Nolan", the slot that is more sure keeps him and the other falls back to its
     * next-best guess (or stays empty) instead of showing a duplicate.
     * Exhaustive search is cheap: 5 slots x [CANDIDATES_PER_SLOT] candidates.
     */
    private fun assignUnique(ranked: List<List<HeroIconMatcher.Candidate>>, threshold: Float, learnedThreshold: Float): List<Int?> {
        val n = ranked.size
        val cur = IntArray(n) { -1 }
        val bestPick = IntArray(n) { -1 }
        var bestTotal = -1f
        val used = HashSet<Int>()

        fun search(slot: Int, total: Float) {
            if (slot == n) {
                if (total > bestTotal) { bestTotal = total; cur.copyInto(bestPick) }
                return
            }
            cur[slot] = -1
            search(slot + 1, total)                       // leave this slot empty
            for ((k, c) in ranked[slot].withIndex()) {
                if (c.score < (if (c.fromLearned) learnedThreshold else threshold) || c.heroId in used) continue
                used += c.heroId
                cur[slot] = k
                search(slot + 1, total + c.score)
                used -= c.heroId
            }
            cur[slot] = -1
        }
        search(0, 0f)

        return List(n) { i -> bestPick[i].takeIf { it >= 0 }?.let { ranked[i][it].heroId } }
    }

    /**
     * The exact portrait crop plus slightly smaller / larger / shifted cuts of it. The layout
     * constants were measured on one screenshot, so real phones can be a few pixels off.
     */
    private fun cropVariants(src: Bitmap, rect: RectF): List<Bitmap> {
        val cx = rect.centerX(); val cy = rect.centerY()
        val w = rect.width(); val h = rect.height()
        fun cut(scale: Float, dx: Float, dy: Float) = cropSafely(src, RectF(
            cx + dx * w - w * scale / 2f, cy + dy * h - h * scale / 2f,
            cx + dx * w + w * scale / 2f, cy + dy * h + h * scale / 2f,
        ))
        return listOfNotNull(
            cut(1.00f, 0f, 0f),
            cut(0.90f, 0f, 0f),
            cut(1.10f, 0f, 0f),
            cut(1.00f, -0.05f, 0f),
            cut(1.00f, 0.05f, 0f),
            cut(1.00f, 0f, -0.05f),
            cut(1.00f, 0f, 0.05f),
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

        /** How many top guesses per slot are kept for the "no duplicate heroes" assignment. */
        private const val CANDIDATES_PER_SLOT = 8

        // Landscape enemy column, all in units of screen height (H), x measured from the right edge.
        private const val LAND_LEFT_FROM_RIGHT = 0.272f    // portrait left edge  = W - 0.272 H
        private const val LAND_RIGHT_FROM_RIGHT = 0.108f   // portrait right edge = W - 0.108 H
        private const val LAND_FIRST_TOP = 46f / 379f      // top of "Player 1" portrait
        private const val LAND_PITCH = 60f / 379f          // distance between portraits
        private const val LAND_SLOT_H = 46f / 379f         // portrait height (excludes name label)

        /** Saves screenshots to Pictures/AceCounterDebug on every Detect. Debug builds only. */
        private val SAVE_DEBUG_IMAGES = com.mlbb.counteroverlay.BuildConfig.DEBUG

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
