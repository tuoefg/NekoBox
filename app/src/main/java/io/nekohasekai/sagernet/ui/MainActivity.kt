package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.backup.BackupEntry
import io.nekohasekai.sagernet.ui.MessageStore.setCurrentActivity
import io.throne.bbox.flutter.BboxFlutterActivity

/**
 * A headless shell (batch L2): the ported Flutter interface is the app's only screen. What is left
 * here is the entry contracts the manifest and the platform keep pointing at this class — the
 * .thrbackup VIEW intent (whose pending Uri [BackupEntry] holds until a restore asks for it),
 * share / open-with / QR-import payloads, and the quick-settings tile preferences — all of which
 * forward to [BboxFlutterActivity].
 */
class MainActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCurrentActivity(this)
        handleIntent(intent)
        if (savedInstanceState == null) {
            forward()
        } else {
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        forward()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // The backup contract runs before any forwarding: BackupEntry stores the Uri either way.
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { BackupEntry.open(this, it) }
        }
    }

    /** Hands the payload to the Flutter host; texts of the QR scanner join into one EXTRA_TEXT. */
    private fun forward() {
        val intent = intent ?: return finishAndForward(null)
        val target = Intent(this, BboxFlutterActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            target.action = Intent.ACTION_VIEW
            target.data = intent.data
        } else {
            val texts = intent.getStringArrayListExtra(EXTRA_IMPORT_TEXTS)
            val text = when {
                !texts.isNullOrEmpty() -> texts.joinToString("\n")
                intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                else -> null
            }
            if (!text.isNullOrBlank()) target.putExtra(Intent.EXTRA_TEXT, text)
        }
        finishAndForward(target)
    }

    private fun finishAndForward(target: Intent?) {
        if (target != null) {
            startActivity(target)
        }
        finish()
    }

    companion object {
        /** Texts to import (the QR scanner): URLs and deep links one by one, the rest as one import. */
        const val EXTRA_IMPORT_TEXTS = "importTexts"
    }
}
