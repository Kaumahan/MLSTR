package com.mlbb.counteroverlay.overlay

import android.content.Context
import android.graphics.Typeface
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

fun ImageView.loadHero(url: String?) = load(url) {
    crossfade(true)
    placeholder(R.drawable.hero_placeholder)
    error(R.drawable.hero_placeholder)
    transformations(CircleCropTransformation())
}

fun ImageView.loadItem(url: String?) = load(url) {
    crossfade(true)
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

    /** Enemy team strip: icons with a remove (×) badge, plus an "add" slot. */
    fun teamStrip(members: List<Pair<String, String?>>, max: Int, onRemove: (Int) -> Unit, onAdd: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            members.forEachIndexed { i, (name, icon) ->
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    contentDescription = "Remove $name"
                    setOnClickListener { onRemove(i) }
                    addView(ImageView(ctx).apply { loadHero(icon) }, LinearLayout.LayoutParams(dp(34), dp(34)))
                    addView(text("$name ✕", 9.5f, R.color.text_secondary).apply { maxLines = 1 })
                }, LinearLayout.LayoutParams(dp(50), WRAP_CONTENT))
            }
            if (members.size < max) {
                addView(text("+ Add", 12f, R.color.accent, bold = true).apply {
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.tab_selected)
                    setOnClickListener { onAdd() }
                }, LinearLayout.LayoutParams(dp(50), dp(34)))
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
    fun chips(labels: List<String>, selected: Int, prefix: String? = null, onPick: (Int) -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(3), 0, dp(1))
        prefix?.let { addView(text(it, 11.5f, R.color.text_secondary).apply { setPadding(0, 0, dp(6), 0) }) }
        labels.forEachIndexed { i, label ->
            addView(text(label, 12f, bold = i == selected).apply {
                setPadding(dp(9), dp(4), dp(9), dp(4))
                setBackgroundResource(if (i == selected) R.drawable.tab_selected else R.drawable.search_bg)
                contentDescription = "$label${if (i == selected) ", selected" else ""}"
                setOnClickListener { onPick(i) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(4) })
        }
    }
}
