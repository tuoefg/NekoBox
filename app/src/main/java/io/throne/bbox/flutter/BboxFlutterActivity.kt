package io.throne.bbox.flutter

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.preference.PreferenceDataStore
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ui.FirstStartPrompts
import io.nekohasekai.sagernet.ui.WifiPermissionFlow
import io.nekohasekai.sagernet.utils.CrashHandler
import io.nekohasekai.sagernet.utils.PlatformNotifications
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
class BboxFlutterActivity : FlutterFragmentActivity(), OnPreferenceDataStoreChangeListener {

    private var bridge: ThroneBoxBridge? = null

    /**
     * The task this activity lives in, kept because an excluded task drops out of [ActivityManager.getAppTasks]:
     * once the switch has hidden us, only this reference can put us back.
     */
    private var recentsTask: ActivityManager.AppTask? = null

    private var openHandler: ((Uri?) -> Unit)? = null
    private var saveHandler: ((Uri?) -> Unit)? = null

    // The first-start prompts and the Wi-Fi walk-through register their launchers at construction,
    // which is what the framework requires; the old UI keeps them as MainActivity fields the same way.
    private val firstStart = FirstStartPrompts(this)
    private val wifiFlow = WifiPermissionFlow(this)

    /** A walk-through is running and owns [wifiFlow]'s single callback slot. */
    private var wifiWaiting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The switch is written through the bridge, so the change arrives here rather than at the next
        // resume: the task leaves the recents list the moment the user turns it on.
        DataStore.configurationStore.registerChangeListener(this)
        if (savedInstanceState == null) {
            firstStart.start()
            if (intent?.getBooleanExtra(PlatformNotifications.EXTRA_WIFI_PERMISSION, false) == true) startWifiFlow()
        }
    }

    override fun onResume() {
        super.onResume()
        applyHideFromRecentApps(DataStore.hideFromRecentApps)
    }

    override fun onDestroy() {
        DataStore.configurationStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == Key.HIDE_FROM_RECENT_APPS) applyHideFromRecentApps(DataStore.hideFromRecentApps)
    }

    /**
     * Turns the "hide from recent tasks" switch into the task flag Android reads. Applied on resume as well
     * as on change, so a value stored by an earlier run still holds on the first frame.
     */
    private fun applyHideFromRecentApps(hide: Boolean) {
        val task = runCatching {
            // A task that is still listed refreshes the reference (a relaunch may have replaced it); an
            // excluded one is not listed any more, and only the reference kept from before can undo it.
            getSystemService(ActivityManager::class.java).appTasks
                .firstOrNull { it.taskInfo.taskId == taskId }
                ?.also { recentsTask = it }
        }.getOrNull() ?: recentsTask
        // A task that ended while the bridge was still writing (a restore that restarts the app) throws here.
        runCatching { task?.setExcludeFromRecents(hide) }
            .onFailure { Logs.w("Failed to set excludeFromRecents: ${it.message}") }
    }

    /**
     * The Wi-Fi walk-through the warning notification asks for. A tap on it while the UI is already up
     * lands in [onNewIntent] and never in [onCreate], so both entry points call this. A walk-through
     * already running keeps the floor: a second one would stack its rationale on top of the first.
     */
    private fun startWifiFlow() {
        if (wifiWaiting) return
        wifiWaiting = true
        // A refused start (another walk-through owns the flow) never calls back, so the flag goes back here.
        if (!wifiFlow.run(onDone = { wifiWaiting = false })) wifiWaiting = false
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        bridge = ThroneBoxBridge(this, flutterEngine.dartExecutor.binaryMessenger).also { it.attach() }
        // The UI really came up: give the next crash its full restart budget back, so a
        // startup crash that always recurs cannot restart the app forever.
        CrashHandler.noteStarted()
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

    /**
     * Walks the user through what Wi-Fi rules need (WifiPermissionFlow); false when nothing was
     * granted or another walk-through already runs. Never blocking: the rule saves either way, the
     * same "a refusal leaves the rules inactive" contract the native rule editor keeps.
     */
    suspend fun ensureWifiPermissions(): Boolean {
        if (wifiWaiting) return false
        wifiWaiting = true
        return try {
            suspendCancellableCoroutine { continuation ->
                val started = wifiFlow.run { granted ->
                    wifiWaiting = false
                    if (continuation.isActive) continuation.resume(granted)
                }
                if (!started) {
                    wifiWaiting = false
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        } catch (e: Throwable) {
            wifiWaiting = false
            throw e
        }
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
        // A hot start still has to run what a cold one would: the Wi-Fi walk-through, then the payload.
        if (intent.getBooleanExtra(PlatformNotifications.EXTRA_WIFI_PERMISSION, false)) startWifiFlow()
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
