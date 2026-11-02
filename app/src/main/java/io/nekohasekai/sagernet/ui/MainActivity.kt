package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import io.nekohasekai.sagernet.ktx.streamUri
import io.nekohasekai.sagernet.ui.MessageStore.setCurrentActivity
import io.throne.bbox.flutter.BboxFlutterActivity

/**
 * A headless shell (batch L2): the ported Flutter interface is the app's only screen. What is left
 * here is the entry contracts the manifest and the platform keep pointing at this class — the
 * .thrbackup VIEW intent, share / open-with / QR-import payloads, and the quick-settings tile
 * preferences — all of which forward to [BboxFlutterActivity].
 *
 * A file handed in from outside is forwarded as its Uri, whatever slot it arrived in: the Flutter
 * interface reads it there and decides between the backup restore and the config importer, which a
 * shell that cannot tell a container from a document must not try to guess.
 */
class MainActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCurrentActivity(this)
        if (savedInstanceState == null) {
            forward()
        } else {
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forward()
    }

    /** Hands the payload to the Flutter host; texts of the QR scanner join into one EXTRA_TEXT. */
    private fun forward() {
        val intent = intent ?: return finishAndForward(null)
        val target = Intent(this, BboxFlutterActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            // The read grant we were handed is tied to this activity, so it has to travel with the payload
            // or the host would find the Uri unreadable a moment after this one finishes.
            .addFlags(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        when {
            intent.action == Intent.ACTION_VIEW && intent.data != null -> {
                target.action = Intent.ACTION_VIEW
                target.data = intent.data
            }
            else -> {
                val texts = intent.getStringArrayListExtra(EXTRA_IMPORT_TEXTS)
                val text = when {
                    !texts.isNullOrEmpty() -> texts.joinToString("\n")
                    intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                    else -> null
                }
                if (text.isNullOrBlank()) {
                    // A shared file, which ACTION_SEND carries in EXTRA_STREAM rather than as text.
                    intent.streamUri?.let {
                        target.action = Intent.ACTION_SEND
                        target.putExtra(Intent.EXTRA_STREAM, it)
                    }
                } else {
                    target.putExtra(Intent.EXTRA_TEXT, text)
                }
            }
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
