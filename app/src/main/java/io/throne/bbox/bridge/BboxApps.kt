package io.throne.bbox.bridge

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.utils.PackageCache
import java.io.ByteArrayOutputStream

/**
 * The installed applications, for the ported UI's access-control screen.
 *
 * This reads Throne's own package cache rather than asking the package manager again, so the list is the one the rest
 * of the app already uses: same row set, same install-time filtering, and a refresh that recomputes it exactly once
 * however many times the UI asks. The screen needs three things Throne's original picker carried implicitly — a label,
 * whether an app can reach the network at all, and whether it is part of the system — so those are resolved here.
 */
object BboxApps {

    data class App(
        val packageName: String,
        val name: String,
        val internet: Boolean,
        val system: Boolean,
        val updatedAt: Long,
    )

    /** Install broadcasting is a process-wide registration; doing it here keeps reads cheap wherever they come from. */
    private fun ensureRegistered() = runCatching { PackageCache.register() }.onFailure { Logs.w(it) }

    /**
     * The catalog as the screen wants it. [refresh] recomputes from the package manager, [system] admits system apps
     * and [nonInternet] admits apps that hold no network permission — Throne's own picker excluded both, which is why
     * the UI can ask for narrower sets without filtering anything itself.
     */
    suspend fun list(refresh: Boolean, system: Boolean, nonInternet: Boolean): List<App> {
        ensureRegistered()
        val snapshot = if (refresh) PackageCache.reload() else PackageCache.snapshot()
        val packages = app.packageManager
        return snapshot.installedApps.entries.mapNotNull { (packageName, info) ->
            val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            if (isSystem && !system) return@mapNotNull null
            val internet = requestsInternet(packages, packageName)
            if (!internet && !nonInternet) return@mapNotNull null
            App(
                packageName = packageName,
                name = snapshot.loadLabel(packageName),
                internet = internet,
                system = isSystem,
                updatedAt = runCatching { packages.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L),
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun requestsInternet(packages: PackageManager, packageName: String): Boolean = runCatching {
        val info = packages.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
    }.getOrDefault(false)

    /** The launcher icon as PNG bytes for Flutter; null when the app ships none. */
    fun icon(packageName: String): ByteArray? {
        ensureRegistered()
        val info = PackageCache.snapshot().installedApps[packageName] ?: return null
        val drawable = runCatching { info.loadIcon(app.packageManager) }.getOrNull() ?: return null
        return runCatching {
            val bitmap = drawable.toBitmap()
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }.onFailure { Logs.w(it) }.getOrNull()
    }

    /** Flutter gets a plain PNG: adaptive icons are flattened onto white so their mask is never transparent. */
    private fun Drawable.toBitmap(size: Int = 96): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFFFFFFFF.toInt())
        setBounds(0, 0, size, size)
        draw(canvas)
        return bitmap
    }
}
