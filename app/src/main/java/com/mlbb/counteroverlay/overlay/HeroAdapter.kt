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

class HeroAdapter(
    private val nameOf: (Int) -> String?,
    private val isInTeam: (Int) -> Boolean,
    private val onTeamToggle: (HeroUi) -> Unit,
    private val onClick: (HeroUi) -> Unit,
) : ListAdapter<HeroUi, HeroAdapter.VH>(Diff) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.heroIcon)
        val name: TextView = view.findViewById(R.id.heroName)
        val sub: TextView = view.findViewById(R.id.heroSub)
        val counters: TextView = view.findViewById(R.id.heroCounters)
        val teamBtn: TextView = view.findViewById(R.id.teamBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_hero, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val hero = getItem(position)
        holder.icon.loadHero(hero.icon)
        holder.name.text = hero.name
        holder.sub.text = hero.subtitle
        val counters = hero.weakAgainst.mapNotNull(nameOf)
        holder.counters.text = if (counters.isEmpty()) "Tap to see counter picks"
        else "Countered by " + counters.joinToString(", ")
        holder.itemView.contentDescription = "${hero.name}, ${hero.subtitle}"
        holder.itemView.setOnClickListener { onClick(hero) }
        val inTeam = isInTeam(hero.id)
        holder.teamBtn.setText(if (inTeam) R.string.in_team else R.string.add_team)
        holder.teamBtn.alpha = if (inTeam) 1f else 0.85f
        holder.teamBtn.setBackgroundResource(if (inTeam) R.drawable.tab_selected else R.drawable.search_bg)
        holder.teamBtn.contentDescription = if (inTeam) "Remove ${hero.name} from enemy team" else "Add ${hero.name} to enemy team"
        holder.teamBtn.setOnClickListener { onTeamToggle(hero) }
    }

    private object Diff : DiffUtil.ItemCallback<HeroUi>() {
        override fun areItemsTheSame(a: HeroUi, b: HeroUi) = a.id == b.id
        override fun areContentsTheSame(a: HeroUi, b: HeroUi) = a == b
    }
}
