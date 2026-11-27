// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import io.nekohasekai.sagernet.SagerNet

/**
 * NekoBox fork-local settings for the Liquid Glass UI.
 *
 * Kept out of DataStore/SettingsRegistry on purpose: this is a fork-only feature and a standalone
 * SharedPreferences file means upstream merges never touch it.
 */
object LiquidPrefs {

    private const val FILE = "liquid_ui"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_JELLY = "jelly_level"
    private const val KEY_BLUR = "dialog_blur_level"
    private const val KEY_GLOW = "ambient_glow_level"
    private const val KEY_SCROLL = "scroll_level"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_SAVE_POWER = "respect_power_save"

    private fun appContext(): Context? = runCatching { SagerNet.application }.getOrNull()

    private fun prefs(context: Context? = null): SharedPreferences? {
        val ctx = context ?: appContext() ?: return null
        return runCatching { ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
            .getOrNull()
    }

    /** True when the Liquid Glass look is active; every styling helper treats "off" as classic UI. */
    var enabled: Boolean
        get() = prefs()?.getBoolean(KEY_ENABLED, true) ?: true
        set(value) {
            prefs()?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        }

    /** 0..5, touch/spring jelly strength on buttons, chips, cards. */
    var jellyLevel: Int
        get() = level(KEY_JELLY, 1)
        set(value) = setLevel(KEY_JELLY, value)

    /** 0..5, Android 12+ dialog backdrop blur strength. */
    var dialogBlurLevel: Int
        get() = level(KEY_BLUR, 3)
        set(value) = setLevel(KEY_BLUR, value)

    /** 0..5, tinted radial glow pools behind the glass surfaces. */
    var ambientGlowLevel: Int
        get() = level(KEY_GLOW, 1)
        set(value) = setLevel(KEY_GLOW, value)

    /** 0..5, elastic overscroll wave in lists. */
    var scrollLevel: Int
        get() = level(KEY_SCROLL, 1)
        set(value) = setLevel(KEY_SCROLL, value)

    var haptics: Boolean
        get() = prefs()?.getBoolean(KEY_HAPTICS, true) ?: true
        set(value) {
            prefs()?.edit()?.putBoolean(KEY_HAPTICS, value)?.apply()
        }

    /** Drop the heavier effects automatically in the system battery saver. */
    var respectPowerSave: Boolean
        get() = prefs()?.getBoolean(KEY_SAVE_POWER, true) ?: true
        set(value) {
            prefs()?.edit()?.putBoolean(KEY_SAVE_POWER, value)?.apply()
        }

    fun isPowerSaveMode(): Boolean {
        val ctx = appContext() ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val manager = runCatching {
            ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull() ?: return false
        return runCatching { manager.isPowerSaveMode }.getOrDefault(false)
    }

    /** Battery saver turns the motion effects off but keeps the static glass surfaces. */
    fun effectsBudgeted(): Boolean = respectPowerSave && isPowerSaveMode()

    private fun level(key: String, default: Int, context: Context? = null): Int {
        if (!enabled || effectsBudgeted()) return 0
        val raw = prefs(context)?.all?.get(key)
        val stored = when (raw) {
            is Int -> raw
            is String -> raw.toIntOrNull()
            else -> null
        } ?: default
        return stored.coerceIn(0, 5)
    }

    private fun setLevel(key: String, value: Int) {
        prefs()?.edit()?.putInt(key, value.coerceIn(0, 5))?.apply()
    }
}
