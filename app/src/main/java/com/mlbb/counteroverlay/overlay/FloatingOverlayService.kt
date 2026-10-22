package com.mlbb.counteroverlay.overlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mlbb.counteroverlay.R
import com.mlbb.counteroverlay.data.DraftAdvisor
import com.mlbb.counteroverlay.data.HeroSearch
import com.mlbb.counteroverlay.data.ServiceLocator
import com.mlbb.counteroverlay.data.model.BuildUi
import com.mlbb.counteroverlay.data.model.CounterStat
import com.mlbb.counteroverlay.data.model.DataSource
import com.mlbb.counteroverlay.data.model.HeroUi
import com.mlbb.counteroverlay.data.model.Lane
import com.mlbb.counteroverlay.data.model.Loadable
import com.mlbb.counteroverlay.data.model.Rank
import com.mlbb.counteroverlay.data.model.RateUi
import com.mlbb.counteroverlay.data.model.Roster
import com.mlbb.counteroverlay.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Floating bubble over any app (including Mobile Legends).
 *  - Tap: open the panel (search enemy hero -> counters by lane, builds, synergies; First Pick tab).
 *  - Drag: move it; it snaps to the nearest screen edge.
 *  - Drag onto the X at the bottom centre: close the bubble.
 */
class FloatingOverlayService : LifecycleService() {

    private enum class Tab { COUNTER, TEAM, FIRST_PICK }

    private lateinit var wm: WindowManager
    private val repository by lazy { ServiceLocator.heroRepository(this) }
    private val themed by lazy { ContextThemeWrapper(this, R.style.Theme_Overlay) }
    private val ui by lazy { OverlayViews(themed) }

    // Windows
    private var bubble: View? = null
    private var panel: View? = null
    private var closeTarget: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    // State
    private var roster = Loadable<Roster>()
    private val heroes get() = roster.value?.heroes.orEmpty()
    private var tab = Tab.COUNTER
    private var query = ""
    private val stack = ArrayDeque<HeroUi>()               // hero detail back stack
    private var matchups = Loadable<List<CounterStat>>()
    private var builds = Loadable<List<BuildUi>>()
    private var buildLane: Lane? = null

    // Rank filter (remembered between sessions)
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var rank = Rank.ALL
    private var rates = Loadable<Map<Int, RateUi>>()
    private var ratesJob: Job? = null

    // Enemy team (up to 5) for the Team tab
    private val team = mutableListOf<Int>()
    private var pickingForTeam = false
    private val teamMatchups = mutableMapOf<Int, Loadable<List<CounterStat>>>()
    private val teamJobs = mutableMapOf<Int, Job>()

    // Skill tags for heroes newer than the bundled snapshot
    private val fetchedTraits = mutableMapOf<Int, List<String>>()

    private var rosterJob: Job? = null
    private var matchupJob: Job? = null
    private var buildJob: Job? = null

    private val adapter = HeroAdapter(
        nameOf = { roster.value?.byId?.get(it)?.name },
        isInTeam = { it in team },
        onTeamToggle = { toggleTeam(it) },
    ) { hero -> if (pickingForTeam) { addToTeam(hero); pickingForTeam = false; render() } else openHero(hero) }

    // ================================================================ lifecycle

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService()!!
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        rank = Rank.from(prefs.getString("rank", null))
        loadUiPrefs()
        addBubble()
        loadRoster(force = false) // warm caches so the first tap is instant
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        listOf(panel, bubble, closeTarget).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        panel = null; bubble = null; closeTarget = null
        isRunning = false
        super.onDestroy()
    }

    // ================================================================ data

    private fun loadRoster(force: Boolean) {
        rosterJob?.cancel()
        rosterJob = lifecycleScope.launch {
            repository.roster(force).collect { roster = it; render() }
        }
    }

    private fun loadHeroData(hero: HeroUi, force: Boolean) {
        matchupJob?.cancel(); buildJob?.cancel()
        matchups = Loadable(isLoading = true)
        matchupJob = lifecycleScope.launch {
            repository.matchups(hero.id, rank, force).collect { matchups = it; render() }
        }
        if (hero.traits.isEmpty() && hero.id !in fetchedTraits) {
            lifecycleScope.launch {
                repository.traits(hero.id).collect { l -> l.value?.let { fetchedTraits[hero.id] = it; render() } }
            }
        }
        val lane = buildLane ?: hero.laneEnums.firstOrNull() ?: Lane.EXP
        buildLane = lane
        builds = Loadable(isLoading = true)
        buildJob = lifecycleScope.launch {
            repository.builds(hero.id, lane, rank, force).collect { builds = it; render() }
        }
    }

    // ================================================================ rendering

    private fun render() {
        val p = panel ?: return
        val hero = stack.lastOrNull()
        val showList = hero == null && (tab == Tab.COUNTER || (tab == Tab.TEAM && pickingForTeam))

        p.findViewById<View>(R.id.listContainer).visibility = if (showList) View.VISIBLE else View.GONE
        p.findViewById<View>(R.id.pageScroll).visibility = if (showList) View.GONE else View.VISIBLE
        p.findViewById<View>(R.id.btnBack).visibility = if (hero != null || (tab == Tab.TEAM && pickingForTeam)) View.VISIBLE else View.GONE
        p.findViewById<View>(R.id.pickHint).visibility = if (showList && tab == Tab.TEAM) View.VISIBLE else View.GONE
        p.findViewById<TextView>(R.id.tabTeam).text = if (team.isEmpty()) getString(R.string.tab_team) else "${getString(R.string.tab_team)} (${team.size})"
        renderRankBar(p)
        p.findViewById<View>(R.id.tabs).visibility = if (hero != null) View.GONE else View.VISIBLE
        p.findViewById<TextView>(R.id.title).text = hero?.name ?: getString(R.string.panel_title)
        p.findViewById<TextView>(R.id.status).text = statusText()
        styleTabs(p)

        when {
            showList -> renderList(p)
            hero != null -> renderDetail(p, hero)
            tab == Tab.TEAM -> renderTeam(p)
            else -> renderFirstPick(p)
        }
    }

    private fun styleTabs(p: View) {
        val counter = p.findViewById<TextView>(R.id.tabCounter)
        val first = p.findViewById<TextView>(R.id.tabFirstPick)
        p.findViewById<TextView>(R.id.tabTeam).setBackgroundResource(if (tab == Tab.TEAM) R.drawable.tab_selected else 0)
        counter.setBackgroundResource(if (tab == Tab.COUNTER) R.drawable.tab_selected else 0)
        first.setBackgroundResource(if (tab == Tab.FIRST_PICK) R.drawable.tab_selected else 0)
    }

    private fun renderList(p: View) {
        val result = HeroSearch.search(heroes, query)
        adapter.submitList(result)
        p.findViewById<TextView>(R.id.emptyText).apply {
            visibility = if (result.isEmpty() && heroes.isNotEmpty()) View.VISIBLE else View.GONE
            text = "No hero found for \"${query.trim()}\". Check the spelling, or search a role like Mage or a lane like Gold."
        }
    }

    private fun statusText(): String {
        val r = roster
        if (r.value == null) return if (r.isLoading) "Loading heroes…" else (r.error ?: "")
        val age = when (r.source) {
            DataSource.NETWORK -> "Updated just now"
            DataSource.DISK_CACHE -> "Updated " + DateUtils.getRelativeTimeSpanString(r.fetchedAtMillis)
            DataSource.BUNDLED -> "Built-in data"
            null -> ""
        }
        return listOfNotNull(
            if (r.isLoading) "Updating…" else age,
            "${heroes.size} heroes",
            r.error,
        ).joinToString("  ·  ")
    }

    /** Clears the page but keeps the scroll position, so expanding a section doesn't jump. */
    private fun pageContent(p: View): LinearLayout {
        val scroll = p.findViewById<ScrollView>(R.id.pageScroll)
        val y = scroll.scrollY
        scroll.post { scroll.scrollTo(0, y) }
        return p.findViewById<LinearLayout>(R.id.pageContent).apply { removeAllViews() }
    }

    private fun name(id: Int) = roster.value?.byId?.get(id)?.name ?: "Hero $id"
    private fun pct(v: Double) = "%.1f%%".format(v * 100)
    private fun signedPct(v: Double) = (if (v >= 0) "+" else "−") + "%.1f%%".format(abs(v) * 100)

    // ---------------------------------------------------------------- compact UI state

    /** "My lane": counter suggestions are shown only for this lane. Remembered. */
    private var myLane: Lane? = null

    /** Which collapsible sections are open. Remembered. */
    private val expanded = mutableSetOf<String>()

    private fun loadUiPrefs() {
        myLane = Lane.from(prefs.getString("my_lane", null))
        expanded.clear()
        expanded += prefs.getStringSet("expanded", setOf(SEC_COUNTERS, SEC_TEAM_BEST))!!
    }

    private fun isOpen(key: String) = key in expanded

    private fun toggle(key: String) {
        if (!expanded.add(key)) expanded.remove(key)
        prefs.edit().putStringSet("expanded", expanded.toSet()).apply()
        render()
    }

    /** Adds a collapsible section; [content] runs only when it's open. */
    private fun LinearLayout.collapsible(key: String, title: String, summary: String?, content: LinearLayout.() -> Unit) {
        val open = isOpen(key)
        addView(ui.collapsible(title, summary, open) { toggle(key) })
        if (open) content()
    }

    private fun LinearLayout.laneSelector(highlight: List<Lane> = emptyList()) {
        val lanes = Lane.entries
        addView(ui.chips(lanes.map { if (it in highlight) "${it.label}•" else it.label }, lanes.indexOf(myLane), "My lane") { i ->
            myLane = if (myLane == lanes[i]) null else lanes[i]   // tap again to clear
            prefs.edit().putString("my_lane", myLane?.apiKey).apply()
            render()
        })
    }

    // ---------------------------------------------------------------- hero page

    private fun renderDetail(p: View, h: HeroUi) {
        val c = pageContent(p)
        val r = roster.value ?: return
        val stats = listOfNotNull(h.winRate?.let { "Win ${pct(it)}" }, h.banRate?.let { "Ban ${pct(it)}" }).joinToString("  ")

        // Compact header: icon, name, stats, roles/lanes.
        c.addView(ui.heroRow(h.icon, h.name, stats.ifEmpty { null }, null, listOf(h.subtitle), onClick = null))

        // 1) Counter picks, only for the lane you play.
        val list = matchups.value
        val lane = myLane
        val lanePicks = if (lane != null && !list.isNullOrEmpty())
            DraftAdvisor.counterPicks(h, list, r).filter { r.byId[it.heroId]?.laneEnums?.contains(lane) == true } else emptyList()
        c.collapsible(SEC_COUNTERS, "Counter picks", lane?.let { "${it.label} lane" }) {
            laneSelector(highlight = h.laneEnums)
            when {
                lane == null -> addView(ui.note("Choose the lane you play to see your best counter picks against ${h.name}. Lanes with • are where ${h.name} plays."))
                list == null && matchups.isLoading -> addView(ui.note("Loading matchups…"))
                list.isNullOrEmpty() -> addView(ui.note(matchups.error ?: "No matchup data for ${h.name} yet."))
                lanePicks.isEmpty() -> addView(ui.note("No strong ${lane.label} lane counters to ${h.name}."))
                else -> {
                    if (lane in h.laneEnums) addView(ui.laneLabel("${lane.label} vs ${lane.label}: direct lane matchup", true))
                    val showAll = isOpen(SEC_COUNTERS_MORE)
                    lanePicks.take(if (showAll) 6 else 3).forEach { addView(counterRow(it, h)) }
                    if (lanePicks.size > 3) addView(ui.text(if (showAll) "Show less" else "Show ${minOf(3, lanePicks.size - 3)} more", 12f, R.color.accent, bold = true).apply {
                        setPadding(ui.dp(38), ui.dp(4), 0, ui.dp(4)); setOnClickListener { toggle(SEC_COUNTERS_MORE) }
                    })
                    if (DraftAdvisor.isRarelyPlayed(h)) addView(ui.note("⚠ ${h.name} is rarely played, so these numbers come from few games."))
                    matchups.error?.let { addView(ui.note(it)) }
                }
            }
        }

        // 2) Don't pick into
        val avoid = if (!list.isNullOrEmpty()) DraftAdvisor.avoid(h, list, r) else emptyList()
        if (avoid.isNotEmpty()) c.collapsible(SEC_AVOID, "Don't pick into ${h.name}", "${avoid.size}") {
            avoid.forEach { p ->
                val hero = r.byId[p.heroId]
                val detail = when {
                    p.official -> "In-game guide: ${h.name} is strong against it"
                    else -> "${h.name} wins ${pct(-p.edge)} more against it"
                }
                addView(ui.heroRow(hero?.icon, name(p.heroId), if (p.edge < 0) signedPct(p.edge) else null, ui.bad,
                    listOf(detail), hero?.let { { openHero(it) } }))
            }
        }

        // 3) In-game guide counters
        if (h.weakAgainst.isNotEmpty()) c.collapsible(SEC_GUIDE, "In-game guide counters", h.weakAgainst.mapNotNull { r.byId[it]?.name }.joinToString(", ")) {
            h.weakAgainst.mapNotNull { r.byId[it] }.forEach { x ->
                addView(ui.heroRow(x.icon, x.name, null, null, listOf(x.lanes.joinToString(" / ")), onClick = { openHero(x) }))
            }
        }

        // 4) Build
        c.collapsible(SEC_BUILD, "Popular build", (buildLane ?: h.laneEnums.firstOrNull())?.let { "${it.label} lane" }) {
            renderBuilds(this, h, r)
        }

        // 5) Synergy
        if (h.synergy.isNotEmpty()) c.collapsible(SEC_SYNERGY, "Pairs well with", h.synergy.mapNotNull { r.byId[it]?.name }.joinToString(", ")) {
            h.synergy.mapNotNull { r.byId[it] }.forEach { x ->
                addView(ui.heroRow(x.icon, x.name, null, null, listOf(x.lanes.joinToString(" / ")), onClick = { openHero(x) }))
            }
        }
    }

    /** Hero with skill tags, falling back to tags fetched for brand-new heroes. */
    private fun withTraits(h: HeroUi) = if (h.traits.isEmpty()) h.copy(traits = fetchedTraits[h.id].orEmpty()) else h

    /** Two short lines max: the number, then the best reason (or guide / low-data mark). */
    private fun counterRow(p: DraftAdvisor.Pick, enemy: HeroUi): View {
        val hero = roster.value?.byId?.get(p.heroId)
        val line1 = buildString {
            if (p.edge > 0) append("${enemy.name} wins ${pct(p.edge)} less")
            if (p.official) append(if (isEmpty()) "★ In-game guide counter" else "  ·  ★ guide")
            if (p.isLowData && !DraftAdvisor.isRarelyPlayed(enemy)) append("  ·  low data")   // rare enemy: one warning below instead
        }
        val reason = hero?.let { DraftAdvisor.reasons(withTraits(it), withTraits(enemy), max = 1).firstOrNull() }
        return ui.heroRow(hero?.icon, name(p.heroId), if (p.edge > 0) signedPct(p.edge) else "★", ui.good,
            listOfNotNull(line1.ifEmpty { null }, reason), hero?.let { { openHero(it) } })
    }

    private fun renderBuilds(c: LinearLayout, h: HeroUi, r: Roster) {
        val lanes = h.laneEnums.ifEmpty { listOf(Lane.EXP) }
        val lane = buildLane ?: lanes.first()
        if (lanes.size > 1) {
            c.addView(ui.chips(lanes.map { it.label }, lanes.indexOf(lane).coerceAtLeast(0)) { i ->
                buildLane = lanes[i]
                buildJob?.cancel()
                builds = Loadable(isLoading = true)
                buildJob = lifecycleScope.launch {
                    repository.builds(h.id, lanes[i], rank).collect { builds = it; render() }
                }
                render()
            })
        }
        val list = builds.value
        when {
            list == null && builds.isLoading -> c.addView(ui.note("Loading build…"))
            list.isNullOrEmpty() -> c.addView(ui.note(builds.error ?: "No build data for this lane."))
            else -> list.take(if (isOpen(SEC_BUILD_MORE)) 2 else 1).forEach { b ->
                val items = b.itemIds.map { id -> r.items[id]?.let { it.name to it.icon } ?: ("Item $id" to null) }
                c.addView(ui.buildCard(items, b.emblem, b.spell,
                    "Win ${pct(b.winRate)}  ·  used in ${pct(b.pickRate)} of ${h.name} games"))
            }.also {
                if (list.size > 1) c.addView(ui.text(if (isOpen(SEC_BUILD_MORE)) "Show less" else "Show another build", 12f, R.color.accent, bold = true).apply {
                    setPadding(0, ui.dp(6), 0, ui.dp(2)); setOnClickListener { toggle(SEC_BUILD_MORE) }
                })
            }
        }
    }

    // ---------------------------------------------------------------- first pick

    private fun renderFirstPick(p: View) {
        val c = pageContent(p)
        val r = roster.value ?: run { c.addView(ui.note("Loading…")); return }
        c.addView(ui.note("Highest win rate in ${rank.label}, last 7 days. Strong blind picks."))
        c.laneSelector()
        val lane = myLane ?: run { c.addView(ui.note("Choose your lane to see the best first picks for it.")); return }
        val rankRates = if (rank == Rank.ALL) null else rates.value
        if (rank != Rank.ALL && rankRates == null) {
            c.addView(ui.note(if (rates.isLoading) "Loading ${rank.label} win rates…" else (rates.error ?: "No data for ${rank.label} yet.")))
            return
        }
        val picks = DraftAdvisor.firstPicks(r, rankRates, perLane = 5).firstOrNull { it.lane == lane }?.heroes.orEmpty()
        if (picks.isEmpty()) { c.addView(ui.note("Win-rate data isn't available yet. Tap refresh when you're online.")); return }
        picks.forEach { (x, rate) ->
            c.addView(ui.heroRow(x.icon, x.name, rate.winRate?.let { "Win ${pct(it)}" }, ui.good,
                listOfNotNull(rate.banRate?.let { "Banned in ${pct(it)} of games" }), onClick = { openHero(x) }))
        }
    }

    private fun loadRates(force: Boolean = false) {
        ratesJob?.cancel()
        if (rank == Rank.ALL) { rates = Loadable(); return }
        rates = Loadable(isLoading = true)
        ratesJob = lifecycleScope.launch { repository.rates(rank, force).collect { rates = it; render() } }
    }

    // ================================================================ team

    private fun renderTeam(p: View) {
        val c = pageContent(p)
        val r = roster.value ?: run { c.addView(ui.note("Loading…")); return }
        c.addView(ui.teamStrip(team.map { id -> name(id) to r.byId[id]?.icon }, MAX_TEAM,
            onRemove = { i -> removeFromTeam(team[i]) },
            onAdd = { pickingForTeam = true; render() }))
        if (team.isEmpty()) {
            c.addView(ui.note("Add the enemy picks (tap + Add, or \"+ Team\" in the Counter list) to find heroes that are good against all of them."))
            return
        }
        c.laneSelector()

        val loading = team.any { teamMatchups[it]?.value == null && teamMatchups[it]?.isLoading != false }
        val tables = team.associateWith { teamMatchups[it]?.value.orEmpty() }
        val picks = DraftAdvisor.teamCounters(team, tables, r)
        if (picks.isEmpty()) {
            c.addView(ui.note(if (loading) "Loading matchups…" else (team.firstNotNullOfOrNull { teamMatchups[it]?.error } ?: "No matchup data yet.")))
            return
        }

        val lane = myLane
        if (lane == null) {
            c.addView(ui.note("Choose your lane to see your best pick against this team."))
        } else {
            val lanePicks = picks.filter { it.score > 0 && r.byId[it.heroId]?.laneEnums?.contains(lane) == true }
            c.collapsible(SEC_TEAM_BEST, "Best ${lane.label} picks vs this team", "${team.size} enemies") {
                if (lanePicks.isEmpty()) addView(ui.note("No ${lane.label} lane hero is good into this whole team."))
                lanePicks.take(if (isOpen(SEC_COUNTERS_MORE)) 6 else 3).forEach { addView(teamRow(it)) }
                if (lanePicks.size > 3) addView(ui.text(if (isOpen(SEC_COUNTERS_MORE)) "Show less" else "Show more", 12f, R.color.accent, bold = true).apply {
                    setPadding(ui.dp(38), ui.dp(4), 0, ui.dp(4)); setOnClickListener { toggle(SEC_COUNTERS_MORE) }
                })
            }
        }
        c.collapsible(SEC_TEAM_ANY, "Best picks, any lane", picks.firstOrNull()?.let { name(it.heroId) }) {
            picks.take(5).forEach { addView(teamRow(it)) }
        }
        val avoid = picks.filter { it.score < 0 }.sortedBy { it.score }.take(3)
        if (avoid.isNotEmpty()) c.collapsible(SEC_TEAM_AVOID, "Avoid against this team", "${avoid.size}") {
            avoid.forEach { addView(teamRow(it, good = false)) }
        }
        if (loading) c.addView(ui.note("Updating…"))
    }

    private fun teamRow(t: DraftAdvisor.TeamPick, good: Boolean = true): View {
        val hero = roster.value?.byId?.get(t.heroId)
        val strongest = t.perEnemy.maxByOrNull { it.value }?.takeIf { it.value > 0 }
        val details = buildList {
            add("Good into ${t.beats} of ${t.enemies}" + (if (good && strongest != null) "  ·  best vs ${name(strongest.key)}" else ""))
        }
        return ui.heroRow(hero?.icon, name(t.heroId), "${signedPct(t.averageEdge)} avg", if (good) ui.good else ui.bad,
            details, hero?.let { { openHero(it) } })
    }

    private fun toggleTeam(hero: HeroUi) {
        if (hero.id in team) removeFromTeam(hero.id) else addToTeam(hero)
        render()
    }

    private fun addToTeam(hero: HeroUi) {
        if (hero.id in team || team.size >= MAX_TEAM) return
        team += hero.id
        loadTeamMatchup(hero.id, force = false)
        adapter.notifyDataSetChanged()
    }

    private fun removeFromTeam(id: Int) {
        team.remove(id)
        teamJobs.remove(id)?.cancel()
        teamMatchups.remove(id)
        adapter.notifyDataSetChanged()
        render()
    }

    private fun loadTeamMatchup(id: Int, force: Boolean) {
        teamJobs[id]?.cancel()
        teamMatchups[id] = Loadable(isLoading = true)
        teamJobs[id] = lifecycleScope.launch {
            repository.matchups(id, rank, force).collect { teamMatchups[id] = it; render() }
        }
    }

    // ================================================================ rank filter

    private fun renderRankBar(p: View) {
        val bar = p.findViewById<LinearLayout>(R.id.rankBar)
        bar.removeAllViews()
        bar.addView(ui.chips(Rank.entries.map { it.shortLabel }, Rank.entries.indexOf(rank)) { i -> setRank(Rank.entries[i]) })
    }

    private fun setRank(newRank: Rank) {
        if (newRank == rank) return
        rank = newRank
        prefs.edit().putString("rank", rank.apiKey).apply()
        stack.lastOrNull()?.let { loadHeroData(it, force = false) }
        team.forEach { loadTeamMatchup(it, force = false) }
        loadRates()
        render()
    }

    // ================================================================ navigation

    private fun openHero(hero: HeroUi) {
        if (stack.lastOrNull()?.id == hero.id) return
        stack.addLast(hero)
        buildLane = null
        loadHeroData(hero, force = false)
        panel?.findViewById<ScrollView>(R.id.pageScroll)?.scrollTo(0, 0)
        hideKeyboard()
        render()
    }

    /** Back: previous hero -> tab root -> minimize panel. */
    private fun goBack() {
        if (stack.isEmpty() && pickingForTeam) { pickingForTeam = false; hideKeyboard(); render(); return }
        if (stack.isNotEmpty()) {
            stack.removeLast()
            stack.lastOrNull()?.let { buildLane = null; loadHeroData(it, force = false) }
                ?: run { matchupJob?.cancel(); buildJob?.cancel() }
            render()
        } else {
            closePanel()
        }
    }

    private fun selectTab(t: Tab) {
        tab = t; stack.clear(); pickingForTeam = false
        if (t == Tab.FIRST_PICK && rank != Rank.ALL && rates.value == null && !rates.isLoading) loadRates()
        matchupJob?.cancel(); buildJob?.cancel()
        hideKeyboard()
        render()
    }

    // ================================================================ bubble

    @SuppressLint("InflateParams")
    private fun addBubble() {
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_bubble, null)
        bubbleParams = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, focusable = false,
        ).apply { x = 0; y = ui.dp(180) }
        view.setOnTouchListener(BubbleTouchListener(view))
        wm.addView(view, bubbleParams)
        bubble = view
    }

    private fun snapBubbleToEdge() {
        val v = bubble ?: return
        val screenW = resources.displayMetrics.widthPixels
        bubbleParams.x = if (bubbleParams.x + v.width / 2 < screenW / 2) 0 else screenW - v.width
        runCatching { wm.updateViewLayout(v, bubbleParams) }
    }

    @SuppressLint("InflateParams")
    private fun showCloseTarget() {
        if (closeTarget != null) return
        val v = LayoutInflater.from(themed).inflate(R.layout.overlay_close_target, null)
        val params = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, focusable = false,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = ui.dp(56)
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        v.alpha = 0f
        wm.addView(v, params)
        v.animate().alpha(1f).setDuration(150).start()
        closeTarget = v
    }

    private fun hideCloseTarget() {
        closeTarget?.let { runCatching { wm.removeView(it) } }
        closeTarget = null
    }

    /** True when the finger (screen coordinates) is over the close target. */
    private fun isOverCloseTarget(rawX: Float, rawY: Float): Boolean {
        val t = closeTarget ?: return false
        if (t.width == 0) return false
        val loc = IntArray(2).also { t.getLocationOnScreen(it) }
        val cx = loc[0] + t.width / 2f
        val cy = loc[1] + t.height / 2f
        return hypot(rawX - cx, rawY - cy) < ui.dp(72)
    }

    private inner class BubbleTouchListener(private val view: View) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@FloatingOverlayService).scaledTouchSlop
        private var startX = 0; private var startY = 0
        private var downX = 0f; private var downY = 0f
        private var dragging = false
        private var overTarget = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x; startY = bubbleParams.y
                    downX = e.rawX; downY = e.rawY
                    dragging = false; overTarget = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        showCloseTarget()
                    }
                    if (dragging) {
                        bubbleParams.x = startX + dx.toInt(); bubbleParams.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, bubbleParams) }
                        val over = isOverCloseTarget(e.rawX, e.rawY)
                        if (over != overTarget) {
                            overTarget = over
                            closeTarget?.animate()?.scaleX(if (over) 1.3f else 1f)?.scaleY(if (over) 1.3f else 1f)?.setDuration(120)?.start()
                            if (over) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideCloseTarget()
                    when {
                        dragging && overTarget -> stopSelf()          // dropped on the X: close
                        dragging -> snapBubbleToEdge()
                        e.actionMasked == MotionEvent.ACTION_UP -> togglePanel()
                    }
                }
            }
            return true
        }
    }

    // ================================================================ panel

    private fun togglePanel() = if (panel == null) openPanel() else closePanel()

    @SuppressLint("InflateParams")
    private fun openPanel() {
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_panel, null) as BackAwareFrame
        val dm = resources.displayMetrics
        val w = min(ui.dp(330), dm.widthPixels - ui.dp(24))
        val h = min(ui.dp(520), (dm.heightPixels * 0.8f).toInt())
        panelParams = baseParams(w, h, focusable = true).apply {
            x = (dm.widthPixels - w) / 2
            y = ui.dp(40)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        view.onBack = ::goBack

        view.findViewById<RecyclerView>(R.id.heroList).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@FloatingOverlayService.adapter
            setHasFixedSize(true)
        }
        view.findViewById<EditText>(R.id.search).apply {
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    if (stack.isEmpty() && (tab == Tab.COUNTER || pickingForTeam)) renderList(view)   // instant, every keystroke
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { closePanel() }
        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { goBack() }
        view.findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            loadRoster(force = true)
            stack.lastOrNull()?.let { loadHeroData(it, force = true) }
            team.forEach { loadTeamMatchup(it, force = true) }
            if (rank != Rank.ALL) loadRates(force = true)
        }
        view.findViewById<View>(R.id.tabCounter).setOnClickListener { selectTab(Tab.COUNTER) }
        view.findViewById<View>(R.id.tabFirstPick).setOnClickListener { selectTab(Tab.FIRST_PICK) }
        view.findViewById<View>(R.id.tabTeam).setOnClickListener { selectTab(Tab.TEAM) }
        view.findViewById<View>(R.id.header).setOnTouchListener(PanelDragListener(view))

        wm.addView(view, panelParams)
        panel = view
        render()
    }

    private fun closePanel() {
        hideKeyboard()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun hideKeyboard() {
        val p = panel ?: return
        getSystemService<android.view.inputmethod.InputMethodManager>()
            ?.hideSoftInputFromWindow(p.windowToken, 0)
        p.findViewById<View>(R.id.search)?.clearFocus()
    }

    private inner class PanelDragListener(private val view: View) : View.OnTouchListener {
        private var startX = 0; private var startY = 0
        private var downX = 0f; private var downY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = panelParams.x; startY = panelParams.y; downX = e.rawX; downY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = startX + (e.rawX - downX).toInt()
                    panelParams.y = startY + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(view, panelParams) }
                }
            }
            return true
        }
    }

    // ================================================================ helpers

    private fun baseParams(w: Int, h: Int, focusable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // NOT_TOUCH_MODAL: touches outside our windows still reach the game.
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun startAsForeground() {
        val nm = getSystemService<NotificationManager>()!!
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, FloatingOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        isRunning = true
    }

    companion object {
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 42
        private const val MAX_TEAM = 5
        private const val SEC_COUNTERS = "counters"
        private const val SEC_COUNTERS_MORE = "counters_more"
        private const val SEC_AVOID = "avoid"
        private const val SEC_GUIDE = "guide"
        private const val SEC_BUILD = "build"
        private const val SEC_BUILD_MORE = "build_more"
        private const val SEC_SYNERGY = "synergy"
        private const val SEC_TEAM_BEST = "team_best"
        private const val SEC_TEAM_ANY = "team_any"
        private const val SEC_TEAM_AVOID = "team_avoid"
        const val ACTION_STOP = "com.mlbb.counteroverlay.STOP"

        @Volatile var isRunning = false
            private set

        fun start(context: Context) =
            context.startForegroundService(Intent(context, FloatingOverlayService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, FloatingOverlayService::class.java))
    }
}
