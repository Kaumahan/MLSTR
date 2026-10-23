package com.mlbb.counteroverlay.overlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mlbb.counteroverlay.CrashReporter
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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import com.mlbb.counteroverlay.detection.DraftScreenDetector
import com.mlbb.counteroverlay.detection.HeroIconMatcher
import com.mlbb.counteroverlay.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Floating bubble over any app (including Mobile Legends).
 *  - Tap: open the panel. Draft tab: tap enemy portraits as they're picked -> instant best
 *    picks for your lane. Hold a portrait for full details. First Pick tab: best blind picks.
 *  - Drag: move it; it snaps to the nearest screen edge.
 *  - Drag onto the X at the bottom centre: close the bubble.
 */
class FloatingOverlayService : LifecycleService() {

    private enum class Tab { DRAFT, FIRST_PICK }

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
    private var tab = Tab.DRAFT
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

    // Enemy picks (up to 5) in the Draft tab
    private val team = mutableListOf<Int>()
    private var teamPage = false                  // full "vs enemy team" page
    private var roleFilter = FILTER_ALL           // grid filter: Recent / All / a role
    private var searchVisible = false
    private val recent = ArrayDeque<Int>()        // recently tapped enemies, newest first
    private var rankExpanded = false

    // Coalesce many data updates into one redraw per frame (less work while the game runs).
    private val mainHandler = Handler(Looper.getMainLooper())
    private var renderPosted = false
    private val teamMatchups = mutableMapOf<Int, Loadable<List<CounterStat>>>()
    private val teamJobs = mutableMapOf<Int, Job>()

    // Skill tags for heroes newer than the bundled snapshot
    private val fetchedTraits = mutableMapOf<Int, List<String>>()

    private var rosterJob: Job? = null
    private var matchupJob: Job? = null
    private var buildJob: Job? = null

    // ── Screen detection ──────────────────────────────────────────────────────
    private val detector by lazy { ServiceLocator.draftScreenDetector(this) }
    private val captureManager by lazy { ServiceLocator.screenCaptureManager(this) }
    private var detectJob: Job? = null
    private var isDetecting = false
    @Volatile private var projectionActive = false   // true while a MediaProjection session is alive

    private val gridAdapter = HeroGridAdapter(
        isSelected = { it in team },
        onTap = { toggleEnemy(it) },
        onHold = { openHero(it) },
    )

    // ================================================================ lifecycle

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService()!!
        // Show the bubble FIRST: an app with a visible overlay window is allowed to run a
        // foreground service; without it Android 12+ can refuse and crash the app.
        val canDraw = Settings.canDrawOverlays(this)
        if (canDraw) {
            rank = Rank.from(prefs.getString("rank", null))
            loadUiPrefs()
            addBubble()
        }
        if (!startAsForeground() || !canDraw || bubble == null) { stopSelf(); return }
        loadRoster(force = false) // warm caches so the first tap is instant
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Screen-capture consent relayed from MainActivity (only present for START_PROJECTION).
        var resultCode = android.app.Activity.RESULT_CANCELED
        var data: Intent? = null
        if (intent?.action == ACTION_START_PROJECTION) {
            resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
        val consentGranted = resultCode == android.app.Activity.RESULT_OK && data != null

        // Every startForegroundService() must be answered with startForeground(), even when
        // we're already running (e.g. the app icon was tapped again). Cheap and idempotent.
        // The mediaProjection type may only be claimed AFTER the user consented, and it must be
        // in place BEFORE getMediaProjection(); otherwise Android throws a SecurityException.
        val promoted = consentGranted && startAsForeground(withProjection = true)
        if (!promoted && !startAsForeground(withProjection = projectionActive)) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STOP) stopSelf()
        if (promoted) {
            run {
                val mp = try {
                    getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data!!)
                } catch (e: RuntimeException) {   // consent token stale/reused: keep the overlay alive
                    CrashReporter.record(this, e); null
                } ?: return@run
                try {
                    captureManager.start(mp) { projectionActive = false }
                } catch (e: RuntimeException) {   // e.g. virtual display refused: overlay keeps working
                    CrashReporter.record(this, e)
                    runCatching { captureManager.stop() }
                    return@run
                }
                projectionActive = true
                // Pre-warm icon matcher after the roster is ready
                lifecycleScope.launch(safe) {
                    val heroList = roster.value?.heroes
                    if (!heroList.isNullOrEmpty()) {
                        ServiceLocator.heroIconMatcher(this@FloatingOverlayService).preload(heroList)
                    }
                }
            }
        }
        // NOT sticky: if Android kills us during a heavy match, don't auto-restart in the
        // background (that restart is what Android 12+ blocks with a crash). Tap the app to reopen.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        panel?.findViewById<RecyclerView>(R.id.heroList)?.adapter = null
        listOf(panel, bubble, closeTarget).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        panel = null; bubble = null; closeTarget = null
        detectJob?.cancel()
        if (captureManager.isRunning()) captureManager.stop()
        isRunning = false
        super.onDestroy()
    }

    // ================================================================ data

    /** Any unexpected error in background work is recorded instead of crashing the overlay. */
    private val safe = kotlinx.coroutines.CoroutineExceptionHandler { _, e -> CrashReporter.record(this, e) }

    private fun loadRoster(force: Boolean) {
        rosterJob?.cancel()
        rosterJob = lifecycleScope.launch(safe) {
            repository.roster(force).collect { roster = it; render() }
        }
    }

    private fun loadHeroData(hero: HeroUi, force: Boolean) {
        matchupJob?.cancel(); buildJob?.cancel()
        matchups = Loadable(isLoading = true)
        matchupJob = lifecycleScope.launch(safe) {
            repository.matchups(hero.id, rank, force).collect { matchups = it; render() }
        }
        if (hero.traits.isEmpty() && hero.id !in fetchedTraits) {
            lifecycleScope.launch(safe) {
                repository.traits(hero.id).collect { l -> l.value?.let { fetchedTraits[hero.id] = it; render() } }
            }
        }
        val lane = buildLane ?: hero.laneEnums.firstOrNull() ?: Lane.EXP
        buildLane = lane
        builds = Loadable(isLoading = true)
        buildJob = lifecycleScope.launch(safe) {
            repository.builds(hero.id, lane, rank, force).collect { builds = it; render() }
        }
    }

    // ================================================================ rendering

    /** Schedules a redraw; many calls in the same frame collapse into one. */
    private fun render() {
        if (panel == null || renderPosted) return
        renderPosted = true
        mainHandler.post { renderPosted = false; doRender() }
    }

    private fun doRender() {
        val p = panel ?: return
        val hero = stack.lastOrNull()
        val showDraft = hero == null && tab == Tab.DRAFT && !teamPage

        p.findViewById<View>(R.id.listContainer).visibility = if (showDraft) View.VISIBLE else View.GONE
        p.findViewById<View>(R.id.pageScroll).visibility = if (showDraft) View.GONE else View.VISIBLE
        p.findViewById<View>(R.id.btnBack).visibility = if (hero != null || teamPage) View.VISIBLE else View.GONE
        p.findViewById<View>(R.id.tabs).visibility = if (hero != null || teamPage) View.GONE else View.VISIBLE
        // Title doubles as the flexible spacer in the header; empty while the tabs are shown.
        p.findViewById<TextView>(R.id.title).text = hero?.name ?: if (teamPage) "Vs enemy team" else ""
        // Status line only when it matters (loading / offline / error): saves a row.
        val r = roster
        val showStatus = r.isLoading || r.error != null || r.value == null || r.source == DataSource.BUNDLED
        p.findViewById<TextView>(R.id.status).apply {
            visibility = if (showStatus) View.VISIBLE else View.GONE
            text = statusText()
        }
        renderRankBar(p)
        styleTabs(p)

        when {
            showDraft -> renderDraft(p)
            hero != null -> renderDetail(p, hero)
            teamPage -> renderTeam(p)
            else -> renderFirstPick(p)
        }
    }

    private fun styleTabs(p: View) {
        p.findViewById<TextView>(R.id.tabCounter).setBackgroundResource(if (tab == Tab.DRAFT) R.drawable.tab_selected else 0)
        p.findViewById<TextView>(R.id.tabFirstPick).setBackgroundResource(if (tab == Tab.FIRST_PICK) R.drawable.tab_selected else 0)
    }

    // ---------------------------------------------------------------- draft page

    private fun renderDraft(p: View) {
        val r = roster.value
        val top = p.findViewById<LinearLayout>(R.id.draftTop).apply { removeAllViews() }
        // Narrow left column in landscape: labels go on their own line so chips fit.
        val stacked = isLandscape()
        if (stacked) top.addView(ui.laneLabel("Enemy picks", false))
        top.addView(ui.enemyRow(team.map { name(it) to r?.byId?.get(it)?.icon }, MAX_TEAM,
            onRemove = { i -> removeFromTeam(team[i]) },
            onHold = { i -> r?.byId?.get(team[i])?.let(::openHero) },
            onClear = ::clearTeam, inlineLabel = !stacked))
        top.laneSelector()
        when {
            r == null -> top.addView(ui.note("Loading heroes…"))
            team.isEmpty() -> top.addView(ui.note(if (isLandscape()) "Tap the enemy picks in the grid →  (hold = details)" else "Tap the enemy picks below ↓  (hold = details)"))
            myLane == null -> top.addView(ui.note("Choose your lane to see your best pick."))
            else -> renderDraftResults(top, r, myLane!!)
        }
        renderRoleBar(p)
        renderGrid(p)
    }

    /** The answer, right under the picks: your 3 best heroes for your lane. */
    private fun renderDraftResults(top: LinearLayout, r: Roster, lane: Lane) {
        val loading = team.any { teamMatchups[it]?.value == null && teamMatchups[it]?.isLoading != false }
        if (team.size == 1) {
            val enemy = r.byId[team[0]] ?: return
            val list = teamMatchups[enemy.id]?.value
            if (list.isNullOrEmpty()) { top.addView(ui.note(if (loading) "Loading…" else teamMatchups[enemy.id]?.error ?: "No data for ${enemy.name} yet.")); return }
            val picks = DraftAdvisor.counterPicks(enemy, list, r).filter { r.byId[it.heroId]?.laneEnums?.contains(lane) == true }.take(3)
            top.addView(ui.laneLabel("Best ${lane.label} picks vs ${enemy.name}", true))
            if (picks.isEmpty()) { top.addView(ui.note("No strong ${lane.label} counter to ${enemy.name}.")); return }
            top.addView(ui.pickCards(picks.map { Triple(name(it.heroId), r.byId[it.heroId]?.icon, if (it.edge > 0) signedPct(it.edge) else "★ guide") },
                onClick = { i -> r.byId[picks[i].heroId]?.let(::openHero) },
                onMore = { openHero(enemy) }))
        } else {
            val tables = team.associateWith { teamMatchups[it]?.value.orEmpty() }
            val picks = DraftAdvisor.teamCounters(team, tables, r)
                .filter { it.score > 0 && r.byId[it.heroId]?.laneEnums?.contains(lane) == true }.take(3)
            top.addView(ui.laneLabel("Best ${lane.label} picks vs ${team.size} enemies", true))
            when {
                picks.isEmpty() && loading -> top.addView(ui.note("Loading…"))
                picks.isEmpty() -> top.addView(ui.note("No ${lane.label} hero is good into this whole team."))
                else -> top.addView(ui.pickCards(picks.map { Triple(name(it.heroId), r.byId[it.heroId]?.icon, "${signedPct(it.averageEdge)} · ${it.beats}/${it.enemies}") },
                    onClick = { i -> r.byId[picks[i].heroId]?.let(::openHero) },
                    onMore = { teamPage = true; render() }))
            }
        }
    }

    private fun renderRoleBar(p: View) {
        val bar = p.findViewById<LinearLayout>(R.id.roleBar).apply { removeAllViews() }
        val filters = buildList { add(FILTER_SEARCH); if (recent.isNotEmpty()) add(FILTER_RECENT); add(FILTER_ALL); addAll(ROLES) }
        val selected = if (searchVisible) 0 else filters.indexOf(roleFilter)
        bar.addView(ui.chips(filters, selected) { i ->
            if (filters[i] == FILTER_SEARCH) setSearchVisible(!searchVisible)
            else { roleFilter = filters[i]; if (searchVisible) setSearchVisible(false); render() }
        })
    }

    private fun renderGrid(p: View) {
        val base = when {
            searchVisible && query.isNotBlank() -> HeroSearch.search(heroes, query)
            roleFilter == FILTER_RECENT -> recent.mapNotNull { roster.value?.byId?.get(it) }
            roleFilter == FILTER_ALL -> heroes
            else -> heroes.filter { roleFilter in it.roles }
        }
        gridAdapter.submitList(base)
        p.findViewById<TextView>(R.id.emptyText).apply {
            visibility = if (base.isEmpty() && heroes.isNotEmpty()) View.VISIBLE else View.GONE
            text = if (searchVisible) "No hero found for \"${query.trim()}\"." else "Nothing here yet."
        }
    }

    /** The panel only takes keyboard focus while searching, so the game keeps focus otherwise. */
    private fun setSearchVisible(show: Boolean) {
        searchVisible = show
        val p = panel ?: return
        val search = p.findViewById<EditText>(R.id.search)
        search.visibility = if (show) View.VISIBLE else View.GONE
        setPanelFocusable(show)
        val imm = getSystemService<android.view.inputmethod.InputMethodManager>()
        if (show) {
            search.requestFocus()
            search.post { imm?.showSoftInput(search, 0) }
        } else {
            query = ""; search.setText("")
            imm?.hideSoftInputFromWindow(p.windowToken, 0)
        }
        render()
    }

    private fun setPanelFocusable(focusable: Boolean) {
        val p = panel ?: return
        panelParams.flags = if (focusable) panelParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else panelParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(p, panelParams) }
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
        recent.clear()
        prefs.getString("recent", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.let { recent.addAll(it) }
        if (recent.isNotEmpty()) roleFilter = FILTER_RECENT
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
        val stacked = isLandscape() && stack.isEmpty() && tab == Tab.DRAFT && !teamPage
        if (stacked) addView(ui.laneLabel("My lane", false))
        addView(ui.chips(lanes.map { if (it in highlight) "${it.label}•" else it.label }, lanes.indexOf(myLane), if (stacked) null else "My lane", fill = stacked) { i ->
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
                buildJob = lifecycleScope.launch(safe) {
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
        ratesJob = lifecycleScope.launch(safe) { repository.rates(rank, force).collect { rates = it; render() } }
    }

    // ================================================================ team

    private fun renderTeam(p: View) {
        val c = pageContent(p)
        val r = roster.value ?: run { c.addView(ui.note("Loading…")); return }
        if (team.isEmpty()) { teamPage = false; render(); return }
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

    private fun toggleEnemy(hero: HeroUi) {
        if (hero.id in team) removeFromTeam(hero.id) else addToTeam(hero)
    }

    private fun addToTeam(hero: HeroUi) {
        if (hero.id in team || team.size >= MAX_TEAM) return
        team += hero.id
        recent.remove(hero.id); recent.addFirst(hero.id)
        while (recent.size > MAX_RECENT) recent.removeLast()
        prefs.edit().putString("recent", recent.joinToString(",")).apply()
        loadTeamMatchup(hero.id, force = false)
        gridAdapter.refreshSelection()
        render()
    }

    private fun removeFromTeam(id: Int) {
        team.remove(id)
        teamJobs.remove(id)?.cancel()
        teamMatchups.remove(id)
        gridAdapter.refreshSelection()
        render()
    }

    private fun clearTeam() {
        team.toList().forEach { teamJobs.remove(it)?.cancel(); teamMatchups.remove(it) }
        team.clear()
        teamPage = false
        gridAdapter.refreshSelection()
        render()
    }

    private fun loadTeamMatchup(id: Int, force: Boolean) {
        teamJobs[id]?.cancel()
        teamMatchups[id] = Loadable(isLoading = true)
        teamJobs[id] = lifecycleScope.launch(safe) {
            repository.matchups(id, rank, force).collect { teamMatchups[id] = it; render() }
        }
    }

    // ================================================================ rank filter

    /** Rank is one small chip in the header; tap it to show the options underneath. */
    private fun renderRankBar(p: View) {
        p.findViewById<TextView>(R.id.rankChip).apply {
            text = "${rank.shortLabel} ▾"
            contentDescription = "Rank filter: ${rank.label}"
            setOnClickListener { rankExpanded = !rankExpanded; render() }
        }
        p.findViewById<View>(R.id.rankScroll).visibility = if (rankExpanded) View.VISIBLE else View.GONE
        val bar = p.findViewById<LinearLayout>(R.id.rankBar)
        bar.removeAllViews()
        if (rankExpanded) bar.addView(ui.chips(Rank.entries.map { it.shortLabel }, Rank.entries.indexOf(rank), "Rank") { i ->
            rankExpanded = false; setRank(Rank.entries[i]); render()
        })
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
        if (stack.isEmpty() && teamPage) { teamPage = false; render(); return }
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
        tab = t; stack.clear(); teamPage = false
        if (searchVisible) setSearchVisible(false)
        if (t == Tab.FIRST_PICK && rank != Rank.ALL && rates.value == null && !rates.isLoading) loadRates()
        matchupJob?.cancel(); buildJob?.cancel()
        hideKeyboard()
        render()
    }

    // ================================================================ screen detection

    /**
     * Called when the user taps the "Detect" button in the panel.
     *
     * Flow:
     *   1. If MediaProjection is not running → send user to MainActivity to grant the
     *      screen capture permission.  MainActivity will call
     *      [ACTION_START_PROJECTION] back to this service once granted.
     *   2. If projection is ready → run the detection pipeline, then auto-populate
     *      the enemy team with whatever heroes were found.
     */
    private fun onDetectTapped() {
        if (!captureManager.isRunning()) {
            // Ask the user for MediaProjection permission via MainActivity.
            val intent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_REQUEST_PROJECTION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            // Show a brief status message in the overlay.
            panel?.findViewById<TextView>(R.id.status)?.text =
                getString(R.string.detect_no_permission)
            return
        }
        if (isDetecting) return   // already scanning
        runDetection()
    }

    private fun runDetection() {
        val heroList = roster.value?.heroes
        if (heroList.isNullOrEmpty()) return

        detectJob?.cancel()
        isDetecting = true
        panel?.findViewById<ImageButton>(R.id.btnDetect)?.alpha = 0.4f
        panel?.findViewById<TextView>(R.id.status)?.text = getString(R.string.detect_scanning)

        detectJob = lifecycleScope.launch(safe) {
            try {
                // Pre-warm icon histograms lazily (no-op if already warmed).
                ServiceLocator.heroIconMatcher(this@FloatingOverlayService).preload(heroList)

                val result = detector.detect(heroList)
                val detected = result.detected
                result.screenshot.recycle()

                if (detected.isEmpty()) {
                    panel?.findViewById<TextView>(R.id.status)?.text =
                        getString(R.string.detect_none)
                } else {
                    // Replace the current enemy team with detected heroes.
                    clearTeam()
                    val rosterMap = roster.value?.byId.orEmpty()
                    detected.forEach { id ->
                        rosterMap[id]?.let { addToTeam(it) }
                    }
                    panel?.findViewById<TextView>(R.id.status)?.text =
                        getString(R.string.detect_found, detected.size)
                    render()
                }
            } finally {
                isDetecting = false
                panel?.findViewById<ImageButton>(R.id.btnDetect)?.alpha = 1f
            }
        }
    }

// ================================================================ bubble

    @SuppressLint("InflateParams")
    private fun addBubble() {
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_bubble, null)
        bubbleParams = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, focusable = false,
        ).apply { x = 0; y = ui.dp(180) }
        view.setOnTouchListener(BubbleTouchListener(view))
        try {
            wm.addView(view, bubbleParams)
            bubble = view
        } catch (e: RuntimeException) {   // permission revoked / window rejected
            CrashReporter.record(this, e)
        }
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
        try { wm.addView(v, params) } catch (e: RuntimeException) { return }
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
                        dragging && overTarget -> mainHandler.post { stopSelf() }   // dropped on the X: close after the touch finishes
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
        val land = isLandscape()
        // Landscape (how MLBB is played): wide and nearly full height, picks left + grid right.
        // Portrait: narrow and stacked.
        val w = if (land) min(ui.dp(600), (dm.widthPixels * 0.62f).toInt()) else min(ui.dp(340), dm.widthPixels - ui.dp(16))
        val h = if (land) min(ui.dp(440), dm.heightPixels - ui.dp(12)) else min(ui.dp(540), (dm.heightPixels * 0.75f).toInt())
        val suffix = if (land) "land" else "port"
        panelParams = baseParams(w, h, focusable = false).apply {
            // Default: left side in landscape, so the enemy picks on the right stay visible.
            x = prefs.getInt("panel_x_$suffix", if (land) ui.dp(8) else (dm.widthPixels - w) / 2)
                .coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
            y = prefs.getInt("panel_y_$suffix", if (land) (dm.heightPixels - h) / 2 else ui.dp(40))
                .coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        configureDraftLayout(view, land)
        view.onBack = ::goBack

        view.findViewById<RecyclerView>(R.id.heroList).apply {
            val gridWidth = if (land) (w * GRID_SHARE_LAND).toInt() else w
            val span = ((gridWidth - ui.dp(6)) / ui.dp(52)).coerceIn(4, 8)
            layoutManager = GridLayoutManager(context, span)
            adapter = gridAdapter
            setHasFixedSize(true)
            itemAnimator = null              // no change animations: cheaper while gaming
            setItemViewCacheSize(30)
        }
        view.findViewById<EditText>(R.id.search).apply {
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    if (stack.isEmpty() && tab == Tab.DRAFT && searchVisible) renderGrid(view)   // instant, every keystroke
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { closePanel() }
        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { goBack() }
        view.findViewById<ImageButton>(R.id.btnDetect).setOnClickListener {
            onDetectTapped()
        }
        view.findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            loadRoster(force = true)
            stack.lastOrNull()?.let { loadHeroData(it, force = true) }
            team.forEach { loadTeamMatchup(it, force = true) }
            if (rank != Rank.ALL) loadRates(force = true)
        }
        view.findViewById<View>(R.id.tabCounter).setOnClickListener { selectTab(Tab.DRAFT) }
        view.findViewById<View>(R.id.tabFirstPick).setOnClickListener { selectTab(Tab.FIRST_PICK) }
        view.findViewById<View>(R.id.header).setOnTouchListener(PanelDragListener(view))

        try {
            wm.addView(view, panelParams)
        } catch (e: RuntimeException) {
            CrashReporter.record(this, e)
            view.findViewById<RecyclerView>(R.id.heroList).adapter = null
            return
        }
        panel = view
        searchVisible = false
        doRender()
    }

    private fun closePanel() {
        hideKeyboard()
        searchVisible = false; query = ""
        panel?.let {
            // Detach the shared grid adapter so old, closed panels never receive updates.
            it.findViewById<RecyclerView>(R.id.heroList)?.adapter = null
            runCatching { wm.removeView(it) }
        }
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
                MotionEvent.ACTION_UP -> {   // remember where the player likes it
                    val suffix = if (isLandscape()) "land" else "port"
                    prefs.edit().putInt("panel_x_$suffix", panelParams.x).putInt("panel_y_$suffix", panelParams.y).apply()
                }
            }
            return true
        }
    }

    // ================================================================ helpers

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Side by side in landscape (picks + results | grid), stacked in portrait. */
    private fun configureDraftLayout(root: View, land: Boolean) {
        val container = root.findViewById<LinearLayout>(R.id.listContainer)
        val left = root.findViewById<View>(R.id.draftScroll)
        val grid = root.findViewById<View>(R.id.gridCol)
        if (land) {
            container.orientation = LinearLayout.HORIZONTAL
            left.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - GRID_SHARE_LAND)
            grid.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, GRID_SHARE_LAND)
        } else {
            container.orientation = LinearLayout.VERTICAL
            left.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            grid.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
    }

    /** Rotating the phone: rebuild the panel for the new shape and keep the bubble on screen. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (panel != null) { closePanel(); openPanel() }
        if (bubble == null) return
        bubble?.let { b ->
            bubbleParams.y = bubbleParams.y.coerceIn(0, (resources.displayMetrics.heightPixels - b.height).coerceAtLeast(0))
            snapBubbleToEdge()
        }
    }

    private fun baseParams(w: Int, h: Int, focusable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // NOT_TOUCH_MODAL: touches outside our windows still reach the game.
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // GPU-render our windows; without this, Service windows draw in software (CPU).
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Returns false if Android refused (e.g. started from the background); never throws. */
    private fun startAsForeground(withProjection: Boolean = false): Boolean = try {
        startAsForegroundUnsafe(withProjection); true
    } catch (e: RuntimeException) {
        CrashReporter.record(this, e); false
    }

    private fun startAsForegroundUnsafe(withProjection: Boolean) {
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

        // Base type is the overlay itself. mediaProjection is added only once capture consent
        // exists (Android 14+ rejects it earlier; Android 10-13 require it for getMediaProjection).
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                if (withProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                if (withProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        isRunning = true
    }

    companion object {
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 42
        private const val MAX_TEAM = 5
        private const val MAX_RECENT = 18
        private const val GRID_SHARE_LAND = 0.58f
        private const val FILTER_SEARCH = "🔍"
        private const val FILTER_RECENT = "Recent"
        private const val FILTER_ALL = "All"
        private val ROLES = listOf("Tank", "Fighter", "Assassin", "Mage", "Marksman", "Support")
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
        /** Sent by MainActivity after the user grants MediaProjection permission. */
        const val ACTION_START_PROJECTION = "com.mlbb.counteroverlay.START_PROJECTION"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        @Volatile var isRunning = false
            private set

        fun start(context: Context) =
            context.startForegroundService(Intent(context, FloatingOverlayService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, FloatingOverlayService::class.java))
    }
}
