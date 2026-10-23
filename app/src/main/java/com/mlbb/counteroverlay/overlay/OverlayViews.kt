package com.mlbb.counteroverlay.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.load
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation
import com.mlbb.counteroverlay.R

// No crossfade: animations cost frames while a game is running underneath.
fun ImageView.loadHero(url: String?) = load(url) {
    crossfade(false)
    placeholder(R.drawable.hero_placeholder)
    error(R.drawable.hero_placeholder)
    transformations(CircleCropTransformation())
}

fun ImageView.loadItem(url: String?) = load(url) {
    crossfade(false)
    placeholder(R.drawable.item_placeholder)
    error(R.drawable.item_placeholder)
    transformations(RoundedCornersTransformation(8f))
}

/** Small builders so the detail and First Pick pages read like a layout. */
class OverlayViews(private val ctx: Context) {

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(ctx, id)
    val good get() = color(R.color.good)
    val bad get() = color(R.color.bad)
    val accent get() = color(R.color.accent)

    fun text(s: CharSequence, sizeSp: Float = 13f, colorRes: Int = R.color.text_primary, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color(colorRes))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.1f)
        }

    fun section(title: String, hint: String? = null): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(4))
        addView(text(title, 14f, R.color.accent, bold = true))
        hint?.let { addView(text(it, 11f, R.color.text_secondary)) }
    }

    /**
     * Tappable section header: "▾ Title ........ summary". The caller adds the
     * section's content only when [expanded], so collapsed sections cost no space.
     */
    fun collapsible(title: String, summary: String?, expanded: Boolean, onToggle: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(34)
            setPadding(0, dp(6), 0, dp(2))
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onToggle() }
            contentDescription = "$title, ${if (expanded) "expanded" else "collapsed"}"
            addView(text(if (expanded) "▾" else "▸", 13f, R.color.accent, bold = true).apply { setPadding(0, 0, dp(6), 0) })
            addView(text(title, 13.5f, R.color.accent, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            summary?.let { addView(text(it, 11f, R.color.text_secondary)) }
        }

    fun note(s: String) = text(s, 11.5f, R.color.text_secondary).apply { setPadding(0, dp(2), 0, dp(2)) }

    fun laneLabel(s: String, highlight: Boolean) =
        text(s, 12f, if (highlight) R.color.accent else R.color.text_primary, bold = true).apply {
            setPadding(0, dp(6), 0, dp(1))
        }

    /**
     * Compact row: icon + name (+ up to 2 detail lines) + right-aligned metric.
     * Tappable when [onClick] is given.
     */
    fun heroRow(
        icon: String?, name: String, metric: String?, metricColor: Int?,
        details: List<String> = emptyList(),
        onClick: (() -> Unit)?,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(40)
        setPadding(dp(2), dp(3), dp(2), dp(3))
        addView(ImageView(ctx).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = ImageView.ScaleType.CENTER_CROP
            loadHero(icon)
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(6), 0)
            addView(text(name, 13f, bold = true))
            details.take(2).forEach { addView(text(it, 11f, R.color.text_secondary).apply { maxLines = 2 }) }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        metric?.let {
            addView(text(it, 12f, bold = true).apply { metricColor?.let(::setTextColor); gravity = Gravity.END })
        }
        contentDescription = (listOf(name) + listOfNotNull(metric) + details).joinToString(", ")
        onClick?.let { click ->
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { click() }
        }
    }

    /**
     * Enemy picks row: 5 small slots. Tap a pick to remove it, hold it for its details.
     * Empty slots are dashed placeholders so it's obvious where picks go.
     */
    fun enemyRow(
        members: List<Pair<String, String?>>, max: Int,
        onRemove: (Int) -> Unit, onHold: (Int) -> Unit, onClear: () -> Unit,
        inlineLabel: Boolean = true,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(2))
        if (inlineLabel) addView(text("Enemy", 11.5f, R.color.text_secondary, bold = true).apply { setPadding(0, 0, dp(6), 0) })
        for (i in 0 until max) {
            val m = members.getOrNull(i)
            addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (m != null) {
                    loadHero(m.second)
                    contentDescription = "${m.first}, tap to remove, hold for details"
                    setOnClickListener { onRemove(i) }
                    setOnLongClickListener { onHold(i); true }
                } else {
                    setImageResource(R.drawable.hero_placeholder)
                    alpha = 0.35f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(4) })
        }
        if (members.isNotEmpty()) addView(text("Clear", 11.5f, R.color.accent, bold = true).apply {
            maxLines = 1
            setPadding(dp(4), dp(6), dp(2), dp(6)); setOnClickListener { onClear() }
        })
    }

    /**
     * The 5 portraits the last Detect looked at, each with the hero it decided on ("?" = none).
     * Tapping one starts "teach" mode: the next hero tapped in the grid is the correct answer.
     */
    fun detectedRow(thumbs: List<Bitmap?>, names: List<String?>, selected: Int?, onTap: (Int) -> Unit, onHold: (Int) -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            addView(text(
                if (selected == null) "Detected · tap = correct it, hold = no hero here. \"Name?\" = unsure guess" else "Now tap the correct hero in the grid",
                10.5f, if (selected == null) R.color.text_secondary else R.color.accent, bold = selected != null))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                for (i in thumbs.indices) {
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setOnClickListener { onTap(i) }
                        setOnLongClickListener { onHold(i); true }
                        addView(ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setImageBitmap(thumbs[i])
                            if (selected == i) setBackgroundResource(R.drawable.pick_ring)
                            setPadding(dp(2), dp(2), dp(2), dp(2))
                        }, LinearLayout.LayoutParams(dp(36), dp(36)))
                        addView(text(names.getOrNull(i) ?: "–", 9f, R.color.text_secondary).apply {
                            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                        }, LinearLayout.LayoutParams(dp(40), WRAP_CONTENT))
                    }, LinearLayout.LayoutParams(dp(40), WRAP_CONTENT).apply { marginEnd = dp(3) })
                }
            })
        }

    /** Up to 3 result cards side by side, plus a "More" button. */
    fun pickCards(picks: List<Triple<String, String?, String>>, onClick: (Int) -> Unit, onMore: (() -> Unit)?): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            picks.forEachIndexed { i, (name, icon, metric) ->
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setBackgroundResource(R.drawable.card_bg)
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                    contentDescription = "$name, $metric"
                    setOnClickListener { onClick(i) }
                    addView(ImageView(ctx).apply { loadHero(icon) }, LinearLayout.LayoutParams(dp(34), dp(34)))
                    addView(text(name, 11f, bold = true).apply { maxLines = 1; gravity = Gravity.CENTER })
                    addView(text(metric, 10.5f, bold = true).apply { setTextColor(good); gravity = Gravity.CENTER })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
            }
            onMore?.let { more ->
                addView(text("More\n›", 11f, R.color.accent, bold = true).apply {
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.search_bg)
                    setOnClickListener { more() }
                }, LinearLayout.LayoutParams(dp(44), MATCH_PARENT))
            }
        }

    /** A card with item icons + names, emblem, spell and win rate. */
    fun buildCard(
        items: List<Pair<String, String?>>,
        emblem: String?, spell: String?,
        footer: String,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        items.forEach { (name, icon) ->
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(1), 0, dp(1))
                addView(ImageView(ctx).apply { loadItem(icon) }, LinearLayout.LayoutParams(dp(22), dp(22)))
                addView(text(name, 12f).apply { setPadding(dp(8), 0, 0, 0) })
            })
        }
        val extras = listOfNotNull(emblem?.let { "Emblem: $it" }, spell?.let { "Spell: $it" })
        if (extras.isNotEmpty()) addView(text(extras.joinToString("  ·  "), 11f, R.color.text_secondary).apply {
            setPadding(0, dp(4), 0, 0)
        })
        addView(text(footer, 10.5f, R.color.text_secondary))
    }

    /** Row of small toggle chips. [selected] = -1 for none. */
    fun chips(labels: List<String>, selected: Int, prefix: String? = null, fill: Boolean = false, onPick: (Int) -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(3), 0, dp(1))
        prefix?.let { addView(text(it, 11.5f, R.color.text_secondary).apply { setPadding(0, 0, dp(6), 0) }) }
        labels.forEachIndexed { i, label ->
            addView(text(label, if (fill) 10.5f else 12f, bold = i == selected).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(if (fill) dp(2) else dp(8), dp(4), if (fill) dp(2) else dp(8), dp(4))
                setBackgroundResource(if (i == selected) R.drawable.tab_selected else R.drawable.search_bg)
                contentDescription = "$label${if (i == selected) ", selected" else ""}"
                setOnClickListener { onPick(i) }
            }, (if (fill) LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                .apply { marginEnd = dp(4) })
        }
    }
}
