package com.mlbb.counteroverlay.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mlbb.counteroverlay.CrashReporter
import com.mlbb.counteroverlay.R
import com.mlbb.counteroverlay.overlay.FloatingOverlayService

/**
 * Opening the app does everything automatically:
 *  1. No permission yet -> explain why (Play "prominent disclosure"), then open the system setting.
 *  2. Permission granted -> start the bubble and close this screen.
 * Android does not let apps run anything at install time, so this happens on first open.
 */
class MainActivity : AppCompatActivity() {

    private var dialog: AlertDialog? = null
    private var askedThisSession = false
    private var crashDialogOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Android 15+ draws edge-to-edge: keep content clear of system bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + v.paddingLeft, v.paddingRight, bars.bottom + v.paddingLeft)
            insets
        }
        findViewById<Button>(R.id.btnPermission).setOnClickListener { openOverlaySettings() }
        findViewById<TextView>(R.id.privacy).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url)))) }
        }
    }

    override fun onResume() {
        super.onResume()
        proceed()
    }

    private fun proceed() {
        if (isFinishing || crashDialogOpen) return
        // After a crash or error: offer to share the saved report before anything else.
        CrashReporter.read(this)?.let { showCrashReport(it); return }
        if (Settings.canDrawOverlays(this)) {
            dialog?.dismiss()
            FloatingOverlayService.start(this)
            finish() // the bubble is the app; no screen needed
        } else if (!askedThisSession) {
            askedThisSession = true
            showDisclosure()
        }
    }

    private fun showCrashReport(report: String) {
        crashDialogOpen = true
        AlertDialog.Builder(this)
            .setTitle("Something went wrong last time")
            .setMessage("A crash report was saved on your phone. Please share it with the developer so it can be fixed. It contains only technical details (phone model, Android version, error), nothing personal.")
            .setPositiveButton("Share report") { _, _ ->
                runCatching {
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Ace Counter Pick crash report")
                            .putExtra(Intent.EXTRA_TEXT, report),
                        "Share crash report"))
                }
                CrashReporter.clear(this)
            }
            .setNeutralButton("Copy") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("crash report", report))
                CrashReporter.clear(this)
            }
            .setNegativeButton("Dismiss") { _, _ -> CrashReporter.clear(this) }
            .setOnDismissListener { crashDialogOpen = false; proceed() }
            .show()
    }

    private fun showDisclosure() {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(R.string.disclosure_body)
            .setPositiveButton(R.string.disclosure_allow) { _, _ -> openOverlaySettings() }
            .setNegativeButton(R.string.disclosure_cancel, null)
            .show()
    }

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }
}
