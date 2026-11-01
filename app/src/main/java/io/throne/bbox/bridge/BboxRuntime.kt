package io.throne.bbox.bridge

import android.content.Context
import io.nekohasekai.sagernet.outbound.config.RoutingInput

/**
 * Process-wide entry point for the B-box bridge.
 *
 * [SagerNet.onCreate] calls [install] in the main process, before any activity exists, so the service connection and
 * the repository listeners are already in place when the first screen composes. Keeping the wiring here rather than
 * in an activity means the state survives rotation and the dashboard has numbers the moment it is drawn.
 */
object BboxRuntime {

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        BboxBridge.install(context)
    }

    fun shutdown() {
        if (!installed) return
        installed = false
        BboxBridge.shutdown()
    }

    /**
     * The routing input a start should use: the user's route profile, rewritten when the dashboard's outbound mode
     * is Global or Direct. Called by [io.nekohasekai.sagernet.bg.proto.CoreConfigs] so a reload from anywhere (tile,
     * shortcut, notification) honours the mode too.
     */
    fun routingInput(base: RoutingInput): RoutingInput = BboxMode.applyTo(base)
}
