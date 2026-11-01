package io.throne.bbox.flutter

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.nekohasekai.sagernet.SagerNet
import io.throne.bbox.bridge.BboxCommands
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The app's entry point: B-box's own Flutter interface, hosted by Throne.
 *
 * The Flutter engine loads the module's assets from the AAR that `flutter build aar` produced, so the screens are
 * B-box's, unmodified. [ThroneBoxBridge] is registered on the engine before the first frame, which is what makes
 * `BoxController.initialize()` find a `ready` answer instead of a missing plugin.
 *
 * This activity is also the leanback launcher, so a TV boots into the same interface. Nothing here changes how the UI
 * behaves on a television beyond what the framework already does: Flutter routes D-pad keys through its own focus
 * tree, so the layout navigates as-is.
 *
 * It also hosts the two system dialogs the ported UI needs — picking a file to import and choosing where a document
 * is written. Those are the only gestures that cannot be answered inside the app, and both are awaited as
 * continuations so the bridge can hand the answer straight back to Dart.
 */
class BboxFlutterActivity : FlutterFragmentActivity() {

    private var bridge: ThroneBoxBridge? = null

    private var openHandler: ((Uri?) -> Unit)? = null
    private var saveHandler: ((Uri?) -> Unit)? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        bridge = ThroneBoxBridge(this, flutterEngine.dartExecutor.binaryMessenger).also { it.attach() }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        bridge?.close()
        bridge = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    /**
     * Lets the user pick a file; null when they back out. One picker at a time: a concurrent call would
     * overwrite the single result slot and leave the first caller hanging forever.
     */
    suspend fun pickDocument(): Uri? = suspendCancellableCoroutine { continuation ->
        if (openHandler != null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        openHandler = { uri -> if (continuation.isActive) continuation.resume(uri) }
        continuation.invokeOnCancellation { openHandler = null }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"),
                REQUEST_OPEN,
            )
        }.onFailure { openHandler = null; continuation.resume(null) }
    }

    /** Lets the user choose where [name] is written; null when they back out. One picker at a time. */
    suspend fun pickSaveTarget(name: String, mime: String): Uri? = suspendCancellableCoroutine { continuation ->
        if (saveHandler != null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        saveHandler = { uri -> if (continuation.isActive) continuation.resume(uri) }
        continuation.invokeOnCancellation { saveHandler = null }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_TITLE, name),
                REQUEST_SAVE,
            )
        }.onFailure { saveHandler = null; continuation.resume(null) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data?.takeIf { resultCode == RESULT_OK }
        when (requestCode) {
            REQUEST_OPEN -> openHandler?.invoke(uri)
            REQUEST_SAVE -> saveHandler?.invoke(uri)
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
        if (requestCode == REQUEST_OPEN) openHandler = null
        if (requestCode == REQUEST_SAVE) saveHandler = null
    }

    /**
     * A share / open-with landing while the UI is up: the bridge keeps the single-delivery contract
     * per intent instance, and the event tells the UI to ask for this one.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bridge?.notifyIntentAvailable()
    }

    /**
     * A television has no notification shade and no tile, so its remote's play/pause button was how the old native UI
     * toggled the proxy. Intercepted before Flutter's key handling, which would otherwise swallow it.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
            SagerNet.isTv
        ) {
            BboxCommands.toggleService()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val REQUEST_OPEN = 4711
        const val REQUEST_SAVE = 4712
    }
}
