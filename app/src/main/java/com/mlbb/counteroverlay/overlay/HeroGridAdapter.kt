package com.mlbb.counteroverlay.overlay

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mlbb.counteroverlay.R
import com.mlbb.counteroverlay.data.model.HeroUi

/** Tap-to-pick portrait grid: tap = add/remove enemy, hold = hero details. No typing needed. */
class HeroGridAdapter(
    private val isSelected: (Int) -> Boolean,
    private val onTap: (HeroUi) -> Unit,
    private val onHold: (HeroUi) -> Unit,
) : ListAdapter<HeroUi, HeroGridAdapter.VH>(Diff) {

    init { setHasStableIds(true) }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.gridIcon)
        val ring: View = view.findViewById(R.id.gridRing)
        val name: TextView = view.findViewById(R.id.gridName)
    }

    override fun getItemId(position: Int) = getItem(position).id.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_hero_grid, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val hero = getItem(position)
        holder.icon.loadHero(hero.icon)
        holder.name.text = hero.name
        bindSelection(holder, hero)
        holder.itemView.setOnClickListener { onTap(hero) }
        holder.itemView.setOnLongClickListener { onHold(hero); true }
    }

    /** Selection-only rebind: no image reload, so toggling picks stays cheap. */
    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) bindSelection(holder, getItem(position))
        else onBindViewHolder(holder, position)
    }

    private fun bindSelection(holder: VH, hero: HeroUi) {
        val selected = isSelected(hero.id)
        holder.ring.visibility = if (selected) View.VISIBLE else View.GONE
        holder.icon.alpha = if (selected) 0.55f else 1f
        holder.itemView.contentDescription = hero.name + if (selected) ", enemy pick" else ""
    }

    fun refreshSelection() = notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)

    private object Diff : DiffUtil.ItemCallback<HeroUi>() {
        override fun areItemsTheSame(a: HeroUi, b: HeroUi) = a.id == b.id
        override fun areContentsTheSame(a: HeroUi, b: HeroUi) = a.name == b.name && a.icon == b.icon
    }

    companion object { private const val PAYLOAD_SELECTION = "sel" }
}
