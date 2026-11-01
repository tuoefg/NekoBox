package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.nekohasekai.sagernet.QuickToggleShortcut
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ui.QuickDisableShortcut
import io.nekohasekai.sagernet.ui.QuickEnableShortcut
import io.throne.bbox.flutter.BboxFlutterActivity

/**
 * The launcher shortcuts, published at runtime: a static shortcuts.xml can only name one hard-coded package, which
 * breaks every build with another application id. The ids differ from the old static ones, which the system keeps
 * immutable while they stay pinned.
 */
object AppShortcuts {

    fun publish(context: Context) {
        try {
            val shortcuts = listOf(
                shortcut(context, "quick_toggle", R.string.quick_toggle, R.drawable.ic_qu_shadowsocks_launcher,
                    QuickToggleShortcut::class.java),
                shortcut(context, "quick_enable", R.string.quick_enable, R.drawable.ic_qu_shadowsocks_launcher,
                    QuickEnableShortcut::class.java),
                shortcut(context, "quick_disable", R.string.quick_disable, R.drawable.ic_qu_shadowsocks_launcher,
                    QuickDisableShortcut::class.java),
                shortcut(context, "quick_scan", R.string.add_profile_methods_scan_qr_code,
                    R.drawable.ic_qu_camera_launcher, io.throne.bbox.flutter.BboxFlutterActivity::class.java)
                    .takeIf { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) },
            ).filterNotNull()
            val current = ShortcutManagerCompat.getDynamicShortcuts(context)
            val unchanged = current.size == shortcuts.size && shortcuts.all { wanted ->
                current.any { it.id == wanted.id && it.shortLabel == wanted.shortLabel }
            }
            if (!unchanged) ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    private fun shortcut(context: Context, id: String, label: Int, icon: Int, target: Class<*>) =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(label))
            .setLongLabel(context.getString(label))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(Intent(context, target).setAction(Intent.ACTION_MAIN))
            .build()
}
