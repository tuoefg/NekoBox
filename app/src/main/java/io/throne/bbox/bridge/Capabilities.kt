package io.throne.bbox.bridge

import android.os.Build
import io.nekohasekai.sagernet.BuildConfig

/**
 * The single source of truth for what the Flutter UI may show.
 *
 * The host advertises this manifest on `ready`, and the UI prunes its navigation from it instead of
 * leaving entries that could only ever open an empty page. Everything false here is a B-box feature
 * that Throne has no counterpart for — a local SSH client, libbox's private request capture, remote
 * sessions, the crash/oom/power report managers, the STUN and network-quality sampling pipelines, the
 * Tailscale/OpenVPN/OpenConnect/usbip endpoints, and Taildrop. Anything true is a screen this
 * bridge actually answers.
 *
 * Keep this list in step with [ThroneBoxBridge]'s dispatch: a capability is only true when the
 * channels behind it exist, so the UI never opens a page that fails every call.
 */
object Capabilities {

    fun map(): Map<String, Any> = mapOf(

        // --- B-box only: nothing in Throne answers these ------------------------------------------
        "terminal" to false,       // no local SSH client, no BoxSSH
        "capture" to false,        // requests page: libbox's private HTTP capture and rewrite engine
        "remote" to false,         // remote control sessions
        "reports" to false,        // crash / oom / power report managers
        "stun" to false,           // STUN page
        "quality" to false,        // network quality sampling page
        "endpoints" to false,      // tailscale / openvpn / openconnect / usbip
        "taildrop" to false,       // taildrop send and receive
        "perAppManaged" to false,  // no Shizuku-managed package list
        "rootQuery" to false,      // packages come from PackageManager only
        "dnsObject" to false,      // no raw DNS object editor
        "routes" to false,         // route profiles still live in the native UI
        "autoSelector" to false,   // auto selector UI is not ported
        "warp" to false,           // WARP is configured through the core, not a dedicated page

        // --- Throne has it ------------------------------------------------------------------------
        "connections" to true,     // core's own clash-api /connections, see BboxApi
        "logs" to true,            // core.log tail, see BboxLogs
        "groups" to true,          // subscription groups
        "memory" to true,          // core's /memory endpoint, see BboxApi.memory
        "proxyGroups" to true,     // groups.list / groups.select / groups.test
        "clashModes" to true,      // mode.set (rule / global / direct)
        "perApp" to true,          // PROXY_APPS / BYPASS_MODE / INDIVIDUAL
        "backup" to true,          // .thrbackup and WebDAV, see BboxBackup
        "lanProxy" to true,        // mixed inbound "Allow LAN" plus its port
        "update" to BuildConfig.IN_APP_UPDATER,
        // The VPN service can write its mixed inbound into the system HTTP settings, which Android
        // only allows from API 29 and only outside the proxy-only service mode.
        "systemProxy" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q),
    )
}
