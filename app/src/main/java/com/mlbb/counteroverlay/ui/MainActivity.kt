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
        if (Settings.canDrawOverlays(this)) {
            dialog?.dismiss()
            FloatingOverlayService.start(this)
            finish() // the bubble is the app; no screen needed
        } else if (!askedThisSession) {
            askedThisSession = true
            showDisclosure()
        }
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
