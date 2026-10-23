package com.mlbb.counteroverlay.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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

    /** True when this Activity was launched specifically to request MediaProjection. */
    private var requestingProjection = false

    /** True when launched from a long-press on Detect to pick a screenshot for testing. */
    private var pickingTestImage = false

    private val testImagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // Copy now: the picker's read permission may not outlive this screen.
            val copied = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.File(cacheDir, FloatingOverlayService.TEST_IMAGE_FILE).outputStream().use { input.copyTo(it) }
                }
            }.isSuccess
            if (copied) {
                startService(Intent(this, FloatingOverlayService::class.java).apply {
                    action = FloatingOverlayService.ACTION_TEST_IMAGE
                })
            }
        }
        finish()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Relay the permission token to the running overlay service.
            val forward = Intent(this, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_START_PROJECTION
                putExtra(FloatingOverlayService.EXTRA_PROJECTION_RESULT_CODE, result.resultCode)
                putExtra(FloatingOverlayService.EXTRA_PROJECTION_DATA, result.data)
            }
            startService(forward)
        }
        // Either way, this activity's job is done.
        finish()
    }

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
        // If we were started to request screen capture, skip the overlay-permission flow.
        if (intent?.action == ACTION_REQUEST_PROJECTION) {
            requestingProjection = true
        } else if (intent?.action == ACTION_PICK_TEST_IMAGE) {
            pickingTestImage = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (pickingTestImage) {
            pickingTestImage = false
            testImagePicker.launch("image/*")
            return
        }
        if (requestingProjection) {
            requestingProjection = false
            showProjectionDisclosure()
            return
        }
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

    // ------------------------------------------------------------------ screen capture

    /**
     * Show a "prominent disclosure" before requesting the MediaProjection permission,
     * as required by Google Play policy.
     */
    private fun showProjectionDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(R.string.detect_disclosure_title)
            .setMessage(R.string.detect_disclosure_body)
            .setPositiveButton(R.string.detect_allow) { _, _ -> launchProjectionChooser() }
            .setNegativeButton(R.string.detect_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun launchProjectionChooser() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    companion object {
        /** Sent by FloatingOverlayService when the user taps the Detect button. */
        const val ACTION_REQUEST_PROJECTION = "com.mlbb.counteroverlay.REQUEST_PROJECTION"
        /** Sent when the user long-presses Detect to test with a gallery screenshot. */
        const val ACTION_PICK_TEST_IMAGE = "com.mlbb.counteroverlay.PICK_TEST_IMAGE"
    }
}
