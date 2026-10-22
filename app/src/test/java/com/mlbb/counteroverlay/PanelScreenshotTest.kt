package com.mlbb.counteroverlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.mlbb.counteroverlay.data.model.CounterStat
import com.mlbb.counteroverlay.data.remote.ApiParsers
import com.mlbb.counteroverlay.data.remote.NetworkModule
import com.mlbb.counteroverlay.overlay.FloatingOverlayService
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Renders the real overlay panel (no network: caches pre-filled from captured API data)
 * and saves screenshots to app/build/screenshots/ for visual review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PanelScreenshotTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val json = NetworkModule.json
    private fun res(n: String) = javaClass.classLoader!!.getResource(n).readText()

    private fun seedCache(key: String, text: String) {
        File(ctx.filesDir, "api_cache").apply { mkdirs() }.resolve("$key.json").writeText(text)
    }

    private fun call(svc: Any, name: String, vararg args: Any?) {
        val m = svc.javaClass.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true; m.invoke(svc, *args)
        repeat(5) { ShadowLooper.idleMainLooper(); Thread.sleep(150) }
    }

    private fun field(svc: Any, name: String): Any? = svc.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(svc)

    private fun shot(svc: Any, file: String) {
        repeat(5) { ShadowLooper.idleMainLooper(); Thread.sleep(150) }
        val panel = field(svc, "panel") as View
        val lp = panel.layoutParams as WindowManager.LayoutParams
        panel.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, lp.width, lp.height)
        val bmp = Bitmap.createBitmap(lp.width, lp.height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(0xFF3A4A3A.toInt()) }.also { panel.draw(it) }
        File("build/screenshots").apply { mkdirs() }.resolve(file).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun renderPanels() {
        shadowOf(ctx.getSystemService(android.app.AppOpsManager::class.java))
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val ser = ListSerializer(CounterStat.serializer())
        seedCache("matchups_v2_77_all", json.encodeToString(ser, ApiParsers.parseMatchups(json, res("matchups_20.json")).map { it }))
        seedCache("matchups_v2_20_all", json.encodeToString(ser, ApiParsers.parseMatchups(json, res("matchups_20.json"))))
        seedCache("matchups_v2_17_all", json.encodeToString(ser, ApiParsers.parseMatchups(json, res("matchups_fanny.json"))))
        seedCache("matchups_v2_84_all", json.encodeToString(ser, ApiParsers.parseMatchups(json, res("matchups_84.json"))))
        seedCache("matchups_v2_1_all", json.encodeToString(ser, ApiParsers.parseMatchups(json, res("matchups_1.json"))))
        ctx.getSharedPreferences("settings", 0).edit().putString("my_lane", "gold").commit()

        val svc = Robolectric.buildService(FloatingOverlayService::class.java).create().get()
        repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(200) }
        call(svc, "openPanel")
        shot(svc, "1_list.png")

        @Suppress("UNCHECKED_CAST")
        val roster = (field(svc, "roster") as com.mlbb.counteroverlay.data.model.Loadable<com.mlbb.counteroverlay.data.model.Roster>).value!!
        val lolita = roster.heroes.first { it.name == "Lolita" }
        call(svc, "openHero", lolita)
        shot(svc, "2_lolita_gold.png")

        call(svc, "toggle", "avoid")
        call(svc, "toggle", "guide")
        shot(svc, "3_lolita_sections_open.png")

        call(svc, "goBack")
        val tabs = svc.javaClass.declaredClasses.first { it.simpleName == "Tab" }.enumConstants
        listOf(17, 84, 1).forEach { id -> call(svc, "addToTeam", roster.byId[id]!!) }
        call(svc, "selectTab", tabs.first { it.toString() == "TEAM" })
        shot(svc, "4_team.png")
        call(svc, "selectTab", tabs.first { it.toString() == "FIRST_PICK" })
        shot(svc, "5_first_pick.png")
    }
}
