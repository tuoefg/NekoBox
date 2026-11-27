// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.ui.theme

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ui.motion.LiquidMotion.animatePopFromOrigin
import io.nekohasekai.sagernet.ui.motion.LiquidMotion.attachJellyTouch
import io.nekohasekai.sagernet.ui.motion.LiquidMotion.attachLiquidJellyScroll
import io.nekohasekai.sagernet.ui.motion.LiquidMotion.bindLiquidLeftIcon
import io.nekohasekai.sagernet.ui.motion.LiquidMotion.playIconSpring
import io.nekohasekai.sagernet.ui.motion.LiquidMotion.playJellyPop
import io.nekohasekai.sagernet.ui.motion.LiquidOrigin
import io.nekohasekai.sagernet.utils.Theme

/**
 * Apple Liquid Glass 2.0 styling helpers.
 * Unified frameless edge-to-edge glass aesthetic across Light/Dark & all 21 user themes on API 24-35.
 */
object LiquidGlass {

    fun isClassic(): Boolean = !LiquidPrefs.enabled

    fun isColorLight(color: Int): Boolean = ColorUtils.calculateLuminance(color) > 0.55

    fun classicOnPrimary(context: Context): Int {
        val primary = runCatching { context.getColorAttr(R.attr.colorPrimary) }.getOrDefault(Color.BLACK)
        return if (isColorLight(primary)) Color.BLACK else Color.WHITE
    }

    fun classicWindowBackgroundColor(context: Context): Int = when {
        isAmoledBlack(context) -> Color.BLACK
        isDark(context) -> Color.parseColor("#121212")
        else -> Color.parseColor("#FAFAFA")
    }

    fun classicCardSurfaceColor(context: Context): Int = when {
        isAmoledBlack(context) -> Color.parseColor("#141414")
        isDark(context) -> Color.parseColor("#1E1E1E")
        else -> Color.WHITE
    }

    private val round20OutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, outline: Outline) {
            outline.setRoundRect(0, 0, v.width, v.height, dp(v.context, 20f))
        }
    }

    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density
    fun dpPx(context: Context, value: Float): Int = dp(context, value).toInt()

    fun isDark(context: Context): Boolean = Theme.usingNightMode() ||
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun isAmoledBlack(context: Context): Boolean {
        val primary = runCatching { context.getColorAttr(R.attr.colorPrimary) }.getOrDefault(0)
        return Color.red(primary) < 16 && Color.green(primary) < 16 && Color.blue(primary) < 16
    }

    /** Resolves crisp theme highlight color cleanly, including AMOLED Black & bright Yellow themes. */
    fun accentColor(context: Context): Int {
        val raw = runCatching { context.getColorAttr(R.attr.selectedColorPrimary) }
            .getOrElse { context.getColorAttr(R.attr.colorPrimary) }
        // In Light mode, darken overly bright accents (e.g. Yellow/Lime) slightly for readable ink
        if (!isDark(context) && ColorUtils.calculateLuminance(raw) > 0.62) {
            return ColorUtils.blendARGB(raw, Color.BLACK, 0.32f)
        }
        return raw
    }

    fun inkPrimary(context: Context): Int {
        val ink = context.getColorAttr(R.attr.appTextPrimary)
        return if (ink != 0) ink else context.getColorAttr(R.attr.primaryOrTextPrimary)
    }

    fun inkSecondary(context: Context): Int {
        val ink = context.getColorAttr(R.attr.appTextSecondary)
        return if (ink != 0) ink else context.getColorAttr(R.attr.accentOrTextSecondary)
    }

    fun dialogBlurLevel(): Int = if (isClassic()) 0 else LiquidPrefs.dialogBlurLevel

    fun isDialogBlurEnabled(): Boolean = dialogBlurLevel() > 0

    fun dialogBlurBehindRadius(level: Int = dialogBlurLevel()): Int = when (level.coerceIn(0, 5)) {
        0 -> 0
        1 -> 14
        2 -> 26
        3 -> 40
        4 -> 56
        else -> 76
    }

    fun dialogBackgroundBlurRadius(level: Int = dialogBlurLevel()): Int = when (level.coerceIn(0, 5)) {
        0 -> 0
        1 -> 10
        2 -> 20
        3 -> 30
        4 -> 42
        else -> 58
    }

    fun applyWindowBlur(window: android.view.Window, level: Int = dialogBlurLevel()) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val effective = if (isClassic()) 0 else level.coerceIn(0, 5)
        runCatching {
            if (effective <= 0) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    blurBehindRadius = 0
                }
                window.setBackgroundBlurRadius(0)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    blurBehindRadius = dialogBlurBehindRadius(effective)
                }
                window.setBackgroundBlurRadius(dialogBackgroundBlurRadius(effective))
            }
        }
    }

    fun ambientGlowLevel(): Int = if (isClassic()) 0 else LiquidPrefs.ambientGlowLevel

    fun isAmbientGlowEnabled(): Boolean = ambientGlowLevel() > 0

    /**
     * Ambient Apple Liquid Glass frosted canvas with configurable 5-level theme-tinted radial
     * glow pools so translucent glass surfaces refract realistic color depth behind them.
     */
    fun createAmbientCanvas(context: Context, levelOverride: Int? = null): Drawable {
        if (isAmoledBlack(context)) return ColorDrawable(Color.BLACK)
        if (isClassic()) return ColorDrawable(classicWindowBackgroundColor(context))
        val dark = isDark(context)
        val accent = accentColor(context)
        val baseColors = if (dark) {
            intArrayOf(Color.parseColor("#070C17"), Color.parseColor("#0E172A"), Color.parseColor("#060A13"))
        } else {
            intArrayOf(Color.parseColor("#E6EEFA"), Color.parseColor("#F2F6FE"), Color.parseColor("#E2EBF8"))
        }
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, baseColors)
        val level = (levelOverride ?: ambientGlowLevel()).coerceIn(0, 5)
        if (level <= 0) return base
        val topAlpha = when (level) {
            1 -> if (dark) 16 else 10
            2 -> if (dark) 26 else 16
            3 -> if (dark) 36 else 22
            4 -> if (dark) 52 else 32
            else -> if (dark) 72 else 46
        }
        val bottomAlpha = when (level) {
            1 -> if (dark) 11 else 7
            2 -> if (dark) 18 else 11
            3 -> if (dark) 26 else 16
            4 -> if (dark) 38 else 24
            else -> if (dark) 54 else 34
        }
        val topRadiusDp = when (level) {
            1 -> 300f
            2 -> 330f
            3 -> 360f
            4 -> 400f
            else -> 440f
        }
        val bottomRadiusDp = when (level) {
            1 -> 260f
            2 -> 290f
            3 -> 320f
            4 -> 360f
            else -> 400f
        }
        val topGlow = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                ColorUtils.setAlphaComponent(accent, topAlpha),
                Color.TRANSPARENT
            )
        ).apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(context, topRadiusDp)
            setGradientCenter(0.14f, 0.08f)
        }
        val bottomGlow = GradientDrawable(
            GradientDrawable.Orientation.BR_TL,
            intArrayOf(
                ColorUtils.setAlphaComponent(accent, bottomAlpha),
                Color.TRANSPARENT
            )
        ).apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(context, bottomRadiusDp)
            setGradientCenter(0.88f, 0.90f)
        }
        return LayerDrawable(arrayOf(base, topGlow, bottomGlow))
    }

    /**
     * Shrinks FastScrollRecyclerView thumb height & width by 50% (52dp -> 26dp, 8dp -> 4dp)
     * into a sleek Apple-style translucent pill scrollbar, and polishes native RecyclerView scrollbars.
     */
    fun RecyclerView.applyLiquidCompactScrollbar() {
        if (isClassic()) return
        val accent = accentColor(context)
        val dark = isDark(context)
        if (this is FastScrollRecyclerView) {
            setThumbColor(ColorUtils.setAlphaComponent(accent, if (dark) 220 else 200))
            setThumbInactiveColor(ColorUtils.setAlphaComponent(accent, if (dark) 115 else 95))
            setTrackColor(Color.TRANSPARENT)
            setAutoHideEnabled(true)
            setAutoHideDelay(850)
            runCatching {
                val sbField = FastScrollRecyclerView::class.java.getDeclaredField("mScrollbar")
                sbField.isAccessible = true
                val scroller = sbField.get(this) ?: return@runCatching
                val cls = scroller.javaClass
                cls.getDeclaredField("mThumbHeight").apply {
                    isAccessible = true
                    setInt(scroller, dpPx(context, 26f))
                }
                cls.getDeclaredField("mThumbWidth").apply {
                    isAccessible = true
                    setInt(scroller, dpPx(context, 4f))
                }
                cls.getDeclaredField("mTrackWidth").apply {
                    isAccessible = true
                    setInt(scroller, dpPx(context, 2f))
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val thumb = ContextCompat.getDrawable(context, R.drawable.bg_liquid_scrollbar_thumb)?.mutate()
                if (thumb != null) {
                    DrawableCompat.setTint(thumb, ColorUtils.setAlphaComponent(accent, if (dark) 130 else 105))
                    verticalScrollbarThumbDrawable = thumb
                }
                scrollBarSize = dpPx(context, 6f)
                scrollBarFadeDuration = 320
            }
        }
    }

    /**
     * Creates a 3-layer double-bevel Apple Liquid Glass drawable:
     * 1. Translucent tinted glass body with a crisp visible outer glass frame (in BOTH Light & Dark)
     * 2. Inset inner white specular bevel ring for authentic glass-edge refraction
     * 3. Top-to-mid soft specular glass sheen highlight
     */
    fun createGlassDrawable(
        context: Context,
        cornerRadiusDp: Float = 24f,
        accentTintRatio: Float = 0.05f,
        selected: Boolean = false,
        strong: Boolean = false,
    ): LayerDrawable {
        if (isClassic()) return LayerDrawable(arrayOf(ColorDrawable(context.getColorAttr(R.attr.colorSurface))))
        val dark = isDark(context)
        val amoled = isAmoledBlack(context)
        val accent = accentColor(context)
        val radius = dp(context, cornerRadiusDp)

        val baseFill = when {
            amoled && strong -> Color.argb(236, 18, 22, 32)
            amoled -> Color.argb(212, 16, 20, 30)
            strong && dark -> Color.argb(234, 22, 32, 50)
            strong -> Color.argb(246, 255, 255, 255)
            dark -> Color.argb(206, 20, 30, 48)
            else -> Color.argb(234, 255, 255, 255)
        }
        val blendedFill = ColorUtils.blendARGB(
            baseFill,
            ColorUtils.setAlphaComponent(accent, Color.alpha(baseFill)),
            if (selected) (accentTintRatio + 0.10f).coerceAtMost(0.22f) else accentTintRatio
        )

        val strokeColor = when {
            selected -> ColorUtils.setAlphaComponent(accent, if (dark) 235 else 210)
            dark -> ColorUtils.blendARGB(
                Color.argb(96, 255, 255, 255),
                ColorUtils.setAlphaComponent(accent, 118),
                0.18f
            )
            else -> ColorUtils.blendARGB(
                Color.argb(54, 15, 23, 42),
                ColorUtils.setAlphaComponent(accent, 78),
                0.28f
            )
        }
        val strokePx = dpPx(context, if (selected) 1.75f else 1.2f).coerceAtLeast(1)

        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(blendedFill)
            setStroke(strokePx, strokeColor)
        }

        // Inner specular glass bevel ring
        val innerRimColor = if (dark) Color.argb(44, 255, 255, 255) else Color.argb(220, 255, 255, 255)
        val innerRadius = (radius - strokePx).coerceAtLeast(0f)
        val innerRim = InsetDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = innerRadius
                setColor(Color.TRANSPARENT)
                setStroke(dpPx(context, 0.9f).coerceAtLeast(1), innerRimColor)
            },
            strokePx
        )

        val specularTop = if (dark) Color.argb(52, 255, 255, 255) else Color.argb(148, 255, 255, 255)
        val specularMid = if (dark) Color.argb(12, 255, 255, 255) else Color.argb(28, 255, 255, 255)
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(specularTop, specularMid, Color.TRANSPARENT)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
        }

        return LayerDrawable(arrayOf(body, innerRim, sheen))
    }

    /** Creates a translucent Apple-style Liquid Glass squircle tile for left-side icons. */
    fun createGlassIconBadgeDrawable(
        context: Context,
        tintColor: Int,
        cornerRadiusDp: Float = 11f,
    ): LayerDrawable {
        val dark = isDark(context)
        val radius = dp(context, cornerRadiusDp)
        val fill = ColorUtils.setAlphaComponent(tintColor, if (dark) 62 else 30)
        val stroke = ColorUtils.setAlphaComponent(tintColor, if (dark) 126 else 74)
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            setStroke(dpPx(context, 1f).coerceAtLeast(1), stroke)
        }
        val glint = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                if (dark) Color.argb(42, 255, 255, 255) else Color.argb(130, 255, 255, 255),
                Color.TRANSPARENT
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
        }
        return LayerDrawable(arrayOf(body, glint))
    }

    /** Styles an ImageView into an Apple Liquid Glass squircle icon badge with clean tinting. */
    fun ImageView.applyLiquidIconBadge(
        tintColor: Int,
        sizeDp: Float = 36f,
        paddingDp: Float = 7.5f,
        cornerRadiusDp: Float = 11f,
        tintGlyph: Boolean = true,
    ) {
        if (isClassic()) {
            background = null
            val padPx = dpPx(context, 6f)
            setPadding(padPx, padPx, padPx, padPx)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = if (tintGlyph) ColorStateList.valueOf(inkSecondary(context)) else null
            clipToOutline = false
            return
        }
        val sizePx = dpPx(context, sizeDp)
        val padPx = dpPx(context, paddingDp)
        layoutParams = (layoutParams ?: ViewGroup.LayoutParams(sizePx, sizePx)).apply {
            width = sizePx
            height = sizePx
        }
        setPadding(padPx, padPx, padPx, padPx)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = createGlassIconBadgeDrawable(context, tintColor, cornerRadiusDp)
        imageTintList = if (tintGlyph) ColorStateList.valueOf(tintColor) else null
        val r = dp(context, cornerRadiusDp)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        clipToOutline = true
    }

    /** Creates a clean frosted Liquid Glass input pill / box (replaces heavy Material grey boxes). */
    fun createLiquidInputDrawable(context: Context, cornerRadiusDp: Float = 999f): GradientDrawable {
        if (isClassic()) return GradientDrawable().apply {
            cornerRadius = dp(context, 4f)
            setColor(context.getColorAttr(R.attr.colorSurface))
            setStroke(dpPx(context, 1f).coerceAtLeast(1), ColorUtils.setAlphaComponent(inkSecondary(context), 80))
        }
        val dark = isDark(context)
        val accent = accentColor(context)
        val fill = if (dark) Color.argb(140, 14, 22, 36) else Color.argb(190, 236, 242, 253)
        val stroke = if (dark) {
            ColorUtils.blendARGB(Color.argb(80, 255, 255, 255), ColorUtils.setAlphaComponent(accent, 96), 0.2f)
        } else {
            ColorUtils.blendARGB(Color.argb(48, 15, 23, 42), ColorUtils.setAlphaComponent(accent, 72), 0.25f)
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, cornerRadiusDp)
            setColor(fill)
            setStroke(dpPx(context, 1.15f).coerceAtLeast(1), stroke)
        }
    }

    /** Styles a Material Chip as a crisp bordered Liquid Glass pill with jelly touch. */
    fun Chip.applyLiquidGlassChip(checked: Boolean = isChecked) {
        if (isClassic()) return
        val dark = isDark(context)
        val accent = accentColor(context)
        val fill = when {
            checked -> ColorUtils.setAlphaComponent(accent, if (dark) 78 else 38)
            dark -> Color.argb(120, 26, 38, 60)
            else -> Color.argb(225, 255, 255, 255)
        }
        val stroke = when {
            checked -> ColorUtils.setAlphaComponent(accent, if (dark) 210 else 165)
            dark -> Color.argb(82, 255, 255, 255)
            else -> Color.argb(52, 15, 23, 42)
        }
        chipBackgroundColor = ColorStateList.valueOf(fill)
        chipStrokeColor = ColorStateList.valueOf(stroke)
        chipStrokeWidth = dp(context, if (checked) 1.35f else 1.1f)
        shapeAppearanceModel = shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(dp(context, 999f))
            .build()
        setTextColor(if (checked) (if (dark) Color.WHITE else accent) else inkPrimary(context))
        checkedIconTint = ColorStateList.valueOf(if (dark) Color.WHITE else accent)
        attachJellyTouch(
            squashX = 0.89f,
            squashY = 0.85f,
            popVelocityX = 3.4f,
            popVelocityY = 4.0f,
            tiltDegrees = 1.6f,
            cornerRadiusDp = 999f
        )
    }

    /** Styles a button as a clean frosted Liquid Glass pill (primary accent or subtle glass). */
    fun View.applyLiquidPillButton(primary: Boolean = false) {
        if (isClassic()) return
        val dark = isDark(context)
        val accent = accentColor(context)
        val fill = when {
            primary -> ColorUtils.setAlphaComponent(accent, if (dark) 215 else 225)
            dark -> Color.argb(170, 26, 38, 60)
            else -> Color.argb(236, 255, 255, 255)
        }
        val stroke = when {
            primary -> Color.argb(if (dark) 120 else 90, 255, 255, 255)
            dark -> Color.argb(90, 255, 255, 255)
            else -> ColorUtils.blendARGB(Color.argb(56, 15, 23, 42), ColorUtils.setAlphaComponent(accent, 90), 0.3f)
        }
        val strokePx = dpPx(context, 1.2f).coerceAtLeast(1)
        if (this is MaterialButton) {
            insetTop = 0
            insetBottom = 0
            cornerRadius = dpPx(context, 999f)
            backgroundTintList = ColorStateList.valueOf(fill)
            strokeColor = ColorStateList.valueOf(stroke)
            strokeWidth = strokePx
            setTextColor(if (primary) Color.WHITE else accent)
            rippleColor = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(if (primary) Color.WHITE else accent, 48)
            )
            isAllCaps = false
            elevation = 0f
            stateListAnimator = null
        } else {
            val radius = dp(context, 999f)
            backgroundTintList = null
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(fill)
                setStroke(strokePx, stroke)
            }
            if (this is TextView) {
                setTextColor(if (primary) Color.WHITE else accent)
                isAllCaps = false
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
            clipToOutline = true
        }
        attachJellyTouch(
            squashX = 0.90f,
            squashY = 0.86f,
            popVelocityX = 3.5f,
            popVelocityY = 4.1f,
            tiltDegrees = 1.8f,
            cornerRadiusDp = 999f
        )
    }

    /** Styles a MaterialCardView as a continuous-corner Apple Liquid Glass card. */
    fun MaterialCardView.applyLiquidGlassCard(selected: Boolean = false, cornerRadiusDp: Float = 24f) {
        if (isClassic()) {
            setCardBackgroundColor(classicCardSurfaceColor(context))
            radius = dp(context, 4f)
            cardElevation = dp(context, 2f)
            strokeWidth = 0
            return
        }
        radius = dp(context, cornerRadiusDp)
        cardElevation = dp(context, if (selected) 7f else 3f)
        maxCardElevation = dp(context, 9f)
        strokeWidth = 0
        setCardBackgroundColor(Color.TRANSPARENT)
        background = createGlassDrawable(context, cornerRadiusDp = cornerRadiusDp, selected = selected)
        clipToOutline = true
    }

    /** Styles a compact pill badge (protocol type, latency tag, etc.) in translucent oval glass. */
    fun View.applyLiquidPillBadge(tintColor: Int) {
        if (isClassic()) {
            background = null
            return
        }
        val dark = isDark(context)
        val fill = ColorUtils.setAlphaComponent(tintColor, if (dark) 52 else 28)
        val stroke = ColorUtils.setAlphaComponent(tintColor, if (dark) 128 else 82)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 999f)
            setColor(fill)
            setStroke(dpPx(context, 1f).coerceAtLeast(1), stroke)
        }
    }

    /** Styles the floating Liquid Glass capsule bottom dock (StatsBar). */
    fun View.applyLiquidFloatingBar() {
        if (isClassic()) {
            setBackgroundColor(context.getColorAttr(R.attr.colorSurface))
            return
        }
        val dark = isDark(context)
        val accent = accentColor(context)
        val radius = dp(context, 28f)
        val base = when {
            isAmoledBlack(context) -> Color.argb(232, 16, 20, 30)
            dark -> Color.argb(228, 20, 30, 48)
            else -> Color.argb(238, 255, 255, 255)
        }
        val tinted = ColorUtils.blendARGB(base, ColorUtils.setAlphaComponent(accent, Color.alpha(base)), 0.08f)
        val border = if (dark) {
            Color.argb(104, 255, 255, 255)
        } else {
            ColorUtils.blendARGB(Color.argb(58, 15, 23, 42), ColorUtils.setAlphaComponent(accent, 84), 0.30f)
        }

        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(tinted)
            setStroke(dpPx(context, 1.3f).coerceAtLeast(1), border)
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                if (dark) Color.argb(58, 255, 255, 255) else Color.argb(160, 255, 255, 255),
                Color.TRANSPARENT
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
        }
        background = LayerDrawable(arrayOf(body, sheen))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
        elevation = dp(context, 10f)
    }

    /**
     * Frameless floating Liquid Glass top header (eliminates the heavy mismatched colored forehead).
     * Transparent AppBarLayout receives status bar inset padding on ALL API levels (24-35),
     * while the slim inner Toolbar floats as a translucent frosted Liquid Glass capsule.
     */
    fun View.applyLiquidHeader() {
        val classic = isClassic()
        setBackgroundColor(if (classic) context.getColorAttr(R.attr.colorPrimary) else Color.TRANSPARENT)
        elevation = if (classic) dp(context, 4f) else 0f
        if (this is AppBarLayout && !classic) {
            stateListAnimator = null
            @Suppress("DEPRECATION")
            targetElevation = 0f
        }

        val hPad = if (classic) 0 else dpPx(context, 12f)
        val topExtra = if (classic) 0 else dpPx(context, 6f)
        val bottomPad = if (classic) 0 else dpPx(context, 4f)

        if (getTag(R.id.liquid_tag_header_inset) != true) {
            setTag(R.id.liquid_tag_header_inset, true)
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val sysTop = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
                ).top
                v.updatePadding(left = hPad, top = sysTop + topExtra, right = hPad, bottom = bottomPad)
                insets
            }
            ViewCompat.requestApplyInsets(this)
        }

        val hasCollapsing = (this as? ViewGroup)?.children?.any { it is CollapsingToolbarLayout } == true
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        if (toolbar != null) {
            if (classic) {
                val primary = context.getColorAttr(R.attr.colorPrimary)
                val onPrimary = classicOnPrimary(context)
                toolbar.setBackgroundColor(primary)
                toolbar.setTitleTextColor(onPrimary)
                toolbar.setSubtitleTextColor(ColorUtils.setAlphaComponent(onPrimary, 192))
                toolbar.applyLiquidToolbarPolish()
                return
            }
            val ink = inkPrimary(context)
            toolbar.setTitleTextColor(ink)
            toolbar.setSubtitleTextColor(inkSecondary(context))
            toolbar.navigationIcon?.let { DrawableCompat.setTint(DrawableCompat.wrap(it).mutate(), ink) }
            toolbar.overflowIcon?.let { DrawableCompat.setTint(DrawableCompat.wrap(it).mutate(), ink) }

            val capsuleRadius = dp(context, 22f)
            toolbar.background = createGlassDrawable(
                context,
                cornerRadiusDp = 22f,
                accentTintRatio = 0.04f,
                strong = true
            )
            toolbar.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, capsuleRadius)
                }
            }
            toolbar.clipToOutline = true
            toolbar.elevation = dp(context, if (hasCollapsing) 3f else 4f)
            toolbar.applyLiquidToolbarPolish()
        }
    }

    /** Styles TabLayout as a crisp Apple-style Liquid Glass segmented pill bar with clear ink contrast. */
    fun TabLayout.applyLiquidTabBar() {
        if (isClassic()) {
            val primary = context.getColorAttr(R.attr.colorPrimary)
            val onPrimary = classicOnPrimary(context)
            val onPrimaryMuted = ColorUtils.setAlphaComponent(onPrimary, 0x99)
            (layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                marginStart = 0
                marginEnd = 0
                topMargin = 0
                bottomMargin = 0
                layoutParams = this
            }
            setBackgroundColor(primary)
            elevation = dp(context, 4f)
            isTabIndicatorFullWidth = false
            setSelectedTabIndicator(GradientDrawable().apply {
                setColor(onPrimary)
                setSize(0, dpPx(context, 2.5f))
            })
            setSelectedTabIndicatorGravity(TabLayout.INDICATOR_GRAVITY_BOTTOM)
            setSelectedTabIndicatorColor(onPrimary)
            tabRippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(onPrimary, 48))
            setTabTextColors(onPrimaryMuted, onPrimary)
            return
        }
        val dark = isDark(context)
        val accent = accentColor(context)
        val outerRadius = dp(context, 20f)
        background = createGlassDrawable(context, cornerRadiusDp = 20f, accentTintRatio = 0.03f, strong = false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, outerRadius)
            }
        }
        clipToOutline = true
        elevation = dp(context, 2.5f)

        // Elevated frosted glass pill indicator (never tinted opaque by Material!)
        val pillFill = if (dark) {
            ColorUtils.blendARGB(Color.argb(190, 40, 54, 82), ColorUtils.setAlphaComponent(accent, 190), 0.36f)
        } else {
            Color.argb(248, 255, 255, 255)
        }
        val pillStroke = if (dark) {
            ColorUtils.setAlphaComponent(accent, 150)
        } else {
            ColorUtils.setAlphaComponent(accent, 95)
        }
        val pillShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 999f)
            setColor(pillFill)
            setStroke(dpPx(context, 1.1f).coerceAtLeast(1), pillStroke)
        }
        val insetH = dpPx(context, 4f)
        val insetV = dpPx(context, 4f)
        setSelectedTabIndicator(InsetDrawable(pillShape, insetH, insetV, insetH, insetV))
        setSelectedTabIndicatorColor(Color.TRANSPARENT)

        val selectedText = if (dark) Color.WHITE else accent
        val unselectedText = inkSecondary(context)
        setTabTextColors(unselectedText, selectedText)
    }

    /** Gives the main DrawerLayout iOS-like spatial depth scaling and rounded corners on slide. */
    fun DrawerLayout.attachLiquidDrawerMotion(contentView: View, drawerView: View) {
        val classic = isClassic()
        setStatusBarBackground(null)
        if (!classic) setScrimColor(Color.argb(if (isDark(context)) 120 else 58, 10, 16, 28))

        ViewCompat.setOnApplyWindowInsetsListener(drawerView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
            )
            v.updatePadding(
                top = bars.top + if (classic) 0 else dpPx(context, 8f),
                bottom = bars.bottom + if (classic) 0 else dpPx(context, 12f)
            )
            insets
        }
        ViewCompat.requestApplyInsets(drawerView)
        if (classic) return

        // Polish NavigationView drawer items with jelly touch & rounded header icon
        drawerView.post {
            val navList = (drawerView as? ViewGroup)?.children?.firstOrNull { it is RecyclerView } as? RecyclerView
            navList?.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (view.isClickable) {
                        view.attachJellyTouch(
                            squashX = 0.94f,
                            squashY = 0.90f,
                            popVelocityX = 2.8f,
                            popVelocityY = 3.4f,
                            tiltDegrees = 1.6f,
                            cornerRadiusDp = 16f
                        )
                    } else if (view is ViewGroup) {
                        // Drawer header icon rounded squircle clip + playful bounce on tap
                        view.children.firstOrNull { it is ImageView }?.let { logo ->
                            val r = dp(context, 12f)
                            logo.outlineProvider = object : ViewOutlineProvider() {
                                override fun getOutline(v: View, outline: Outline) {
                                    outline.setRoundRect(0, 0, v.width, v.height, r)
                                }
                            }
                            logo.clipToOutline = true
                            logo.setOnClickListener { it.playIconSpring(1.1f) }
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            })
        }

        val maxRadius = dp(context, 28f)
        val outline = object : ViewOutlineProvider() {
            var currentRadius = 0f
            override fun getOutline(view: View, out: Outline) {
                out.setRoundRect(0, 0, view.width, view.height, currentRadius)
            }
        }
        contentView.outlineProvider = outline
        addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawer: View, slideOffset: Float) {
                val scale = 1f - slideOffset * 0.055f
                contentView.scaleX = scale
                contentView.scaleY = scale
                contentView.translationX = drawer.width * slideOffset * 0.12f
                outline.currentRadius = maxRadius * slideOffset
                contentView.clipToOutline = slideOffset > 0.01f
                contentView.invalidateOutline()
            }
        })
    }

    /**
     * Applies Liquid Glass backdrop blur (API 31+), touch-centered radial spring pop-out,
     * and tactile jelly buttons to any Dialog or AlertDialog.
     */
    fun Dialog.applyLiquidDialogWindow(applyGlassBackground: Boolean = false, anchor: View? = null) {
        if (isClassic()) return
        if (anchor != null) LiquidOrigin.capture(anchor)
        val win = window ?: return
        val decor = win.decorView
        val alreadyPolished = decor.getTag(R.id.liquid_tag_dialog_polished) == true
        decor.setTag(R.id.liquid_tag_dialog_polished, true)

        win.setWindowAnimations(R.style.Animation_SagerNet_LiquidDialog)
        win.setDimAmount(if (isDark(context)) 0.44f else 0.22f)
        if (applyGlassBackground) {
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyWindowBlur(win)
        }
        if (!alreadyPolished) {
            // Animate dialog surface radially from the user's exact tap origin!
            val targetSurface = win.findViewById<View>(android.R.id.content) ?: decor
            targetSurface.animatePopFromOrigin(anchor)

            decor.post {
                if (this is AlertDialog) {
                    listOf(
                        android.content.DialogInterface.BUTTON_POSITIVE,
                        android.content.DialogInterface.BUTTON_NEGATIVE,
                        android.content.DialogInterface.BUTTON_NEUTRAL
                    ).forEach { which ->
                        getButton(which)?.attachJellyTouch(
                            squashX = 0.87f,
                            squashY = 0.83f,
                            popVelocityX = 3.6f,
                            popVelocityY = 4.2f,
                            tiltDegrees = 2.0f,
                            cornerRadiusDp = 999f
                        )
                    }
                }
                decor.polishLiquidInteractiveTree()
            }
        }
    }

    /** Universal one-call helper so every MaterialAlertDialogBuilder pops out from the tap origin. */
    fun MaterialAlertDialogBuilder.showLiquid(anchor: View? = null): AlertDialog {
        if (anchor != null) LiquidOrigin.capture(anchor)
        val dialog = create()
        dialog.setOnShowListener { dialog.applyLiquidDialogWindow(anchor = anchor) }
        dialog.show()
        return dialog
    }

    /** Styles a Snackbar as a floating Liquid Glass capsule with jelly pop-in. */
    fun Snackbar.applyLiquidSnackbar(): Snackbar {
        if (isClassic()) return this
        val snackView = view
        snackView.background = ContextCompat.getDrawable(context, R.drawable.bg_liquid_snackbar)
        val lp = snackView.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            val hMargin = dpPx(context, 14f)
            val vMargin = dpPx(context, 10f)
            lp.setMargins(hMargin, vMargin, hMargin, vMargin)
            snackView.layoutParams = lp
        }
        snackView.elevation = dp(context, 10f)
        addCallback(object : Snackbar.Callback() {
            override fun onShown(sb: Snackbar?) {
                snackView.playJellyPop(0.85f)
            }
        })
        return this
    }

    /** Attaches crisp Light/Dark icon tint & jelly touch feedback to Toolbar navigation & action buttons. */
    fun Toolbar.applyLiquidToolbarPolish() {
        post {
            val classic = isClassic()
            val ink = if (classic) classicOnPrimary(context) else inkPrimary(context)
            val inkHint = ColorUtils.setAlphaComponent(ink, 178)
            navigationIcon?.let { DrawableCompat.setTint(DrawableCompat.wrap(it).mutate(), ink) }
            overflowIcon?.let { DrawableCompat.setTint(DrawableCompat.wrap(it).mutate(), ink) }
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                item.icon?.let { icon ->
                    DrawableCompat.setTint(DrawableCompat.wrap(icon).mutate(), ink)
                }
                val actionView = item.actionView
                if (actionView is androidx.appcompat.widget.SearchView) {
                    tintSearchView(actionView, ink, inkHint)
                }
            }
            if (classic) return@post
            for (child in children) {
                if (child is ImageButton) {
                    child.attachJellyTouch(
                        squashX = 0.84f,
                        squashY = 0.80f,
                        popVelocityX = 3.8f,
                        popVelocityY = 4.4f,
                        tiltDegrees = 2.0f,
                        cornerRadiusDp = 14f
                    )
                } else if (child is ViewGroup) {
                    for (grandChild in child.children) {
                        grandChild.attachJellyTouch(
                            squashX = 0.84f,
                            squashY = 0.80f,
                            popVelocityX = 3.8f,
                            popVelocityY = 4.4f,
                            tiltDegrees = 2.0f,
                            cornerRadiusDp = 14f
                        )
                    }
                }
            }
        }
    }

    private fun tintSearchView(searchView: androidx.appcompat.widget.SearchView, ink: Int, hintColor: Int) {
        val iconIds = intArrayOf(
            androidx.appcompat.R.id.search_button,
            androidx.appcompat.R.id.search_close_btn,
            androidx.appcompat.R.id.search_mag_icon,
            androidx.appcompat.R.id.search_go_btn,
            androidx.appcompat.R.id.search_voice_btn
        )
        for (id in iconIds) {
            searchView.findViewById<ImageView>(id)?.apply {
                imageTintList = ColorStateList.valueOf(ink)
                drawable?.let { DrawableCompat.setTint(DrawableCompat.wrap(it).mutate(), ink) }
            }
        }
        searchView.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)?.apply {
            setTextColor(ink)
            setHintTextColor(hintColor)
        }
    }

    /**
     * Transforms flat AndroidX Preference RecyclerViews into Apple-style rounded Liquid Glass
     * tiles with floating section headers, clean margins, and tactile jelly touch on every row.
     */
    fun RecyclerView.applyLiquidPreferenceStyle() {
        if (isClassic()) return
        if (getTag(R.id.liquid_tag_pref_styled) == true) return
        setTag(R.id.liquid_tag_pref_styled, true)
        clipToPadding = false
        attachLiquidJellyScroll()

        val sidePad = dpPx(context, 12f)
        val topPad = dpPx(context, 8f)
        val bottomPad = dpPx(context, 96f)
        updatePadding(left = sidePad, top = topPad, right = sidePad, bottom = bottomPad)

        addItemDecoration(object : RecyclerView.ItemDecoration() {
            val rowGap = dpPx(context, 4.5f)
            val catTopGap = dpPx(context, 12f)
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val isCategory = isPreferenceCategory(view)
                outRect.left = 0
                outRect.right = 0
                outRect.top = if (isCategory) catTopGap else rowGap
                outRect.bottom = if (isCategory) dpPx(context, 3f) else rowGap
            }
        })
    }

    private fun isPreferenceCategory(view: View): Boolean =
        !view.isClickable && !view.isFocusable && view.findViewById<View>(android.R.id.widget_frame) == null

    internal fun bindPreferenceRow(view: View) {
        val context = view.context
        val accent = accentColor(context)
        if (isPreferenceCategory(view)) {
            view.setBackgroundColor(Color.TRANSPARENT)
            view.setPadding(dpPx(context, 8f), dpPx(context, 6f), dpPx(context, 8f), dpPx(context, 4f))
            view.findViewById<TextView>(android.R.id.title)?.apply {
                setTextColor(accent)
                textSize = 13f
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(context, 99f)
                        setColor(accent)
                        setSize(dpPx(context, 3.5f), dpPx(context, 14f))
                    },
                    null, null, null
                )
                compoundDrawablePadding = dpPx(context, 8f)
            }
            return
        }
        val padH = dpPx(context, 14f)
        val padV = dpPx(context, 10f)
        val r = dp(context, 20f)
        view.minimumHeight = dpPx(context, 56f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.defaultFocusHighlightEnabled = false
        }
        val glassBg = createGlassDrawable(context, cornerRadiusDp = 20f, accentTintRatio = 0.045f)
        view.background = if (view.isClickable) {
            RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 36)),
                glassBg,
                GradientDrawable().apply {
                    cornerRadius = r
                    setColor(Color.WHITE)
                }
            )
        } else {
            glassBg
        }
        view.setPadding(padH, padV, padH, padV)
        view.elevation = dp(context, 2f)
        view.outlineProvider = round20OutlineProvider
        view.clipToOutline = true
        (view as? ViewGroup)?.clipChildren = false

        // Style left icon into an Apple Liquid Glass squircle tile + bind independent left-icon animation!
        val iconView = view.findViewById<ImageView>(android.R.id.icon)
        val iconFrame = view.findViewById<View>(androidx.preference.R.id.icon_frame)
            ?: iconView?.parent as? View
        var animatedIcon: View? = null
        if (iconView != null && iconView.visibility == View.VISIBLE && iconView.drawable != null) {
            iconFrame?.visibility = View.VISIBLE
            iconFrame?.minimumWidth = dpPx(context, 48f)
            (iconFrame as? ViewGroup)?.apply {
                clipChildren = false
                clipToPadding = false
                setPadding(0, 0, dpPx(context, 12f), 0)
                if (this is LinearLayout) gravity = Gravity.CENTER_VERTICAL
            }
            iconView.applyLiquidIconBadge(
                tintColor = accent,
                sizeDp = 36f,
                paddingDp = 7.5f,
                cornerRadiusDp = 11f,
                tintGlyph = true
            )
            iconView.alpha = if (view.isEnabled) 1f else 0.45f
            animatedIcon = iconView
        } else {
            // Never leave an awkward blank left gap if a preference has no icon
            iconFrame?.visibility = View.GONE
        }

        view.findViewById<TextView>(android.R.id.title)?.setTextColor(inkPrimary(context))
        view.findViewById<TextView>(android.R.id.summary)?.setTextColor(inkSecondary(context))

        view.bindLiquidLeftIcon(animatedIcon)
        if (view.isClickable) {
            view.attachJellyTouch(
                squashX = 0.94f,
                squashY = 0.90f,
                popVelocityX = 3.0f,
                popVelocityY = 3.6f,
                tiltDegrees = 2.0f,
                cornerRadiusDp = 20f,
                leftIcon = animatedIcon
            )
        } else {
            view.setOnTouchListener(null)
        }
    }

    /** Recursively attaches glass framing, jelly scroll & touch feedback across forms, cards, and lists. */
    fun View.polishLiquidInteractiveTree() {
        if (isClassic()) return
        when (this) {
            is RecyclerView -> {
                attachLiquidJellyScroll()
                addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) {
                        if (view is MaterialCardView && view.background !is LayerDrawable) {
                            view.applyLiquidGlassCard(cornerRadiusDp = 22f)
                        }
                        if (view.isClickable || view.isFocusable) {
                            view.attachJellyTouch(
                                squashX = 0.94f,
                                squashY = 0.90f,
                                popVelocityX = 2.8f,
                                popVelocityY = 3.4f,
                                tiltDegrees = 2.0f,
                                cornerRadiusDp = 20f
                            )
                        }
                    }

                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                })
            }

            is MaterialCardView -> {
                if (background !is LayerDrawable) applyLiquidGlassCard(cornerRadiusDp = 22f)
                if (isClickable || isFocusable) attachJellyTouch()
            }
            is Chip -> applyLiquidGlassChip()
            is MaterialButton -> attachJellyTouch(
                squashX = 0.89f,
                squashY = 0.85f,
                popVelocityX = 3.6f,
                popVelocityY = 4.2f,
                cornerRadiusDp = 999f
            )

            is SwitchCompat -> attachJellyTouch(
                squashX = 0.88f,
                squashY = 0.84f,
                tiltDegrees = 1.5f,
                cornerRadiusDp = 999f,
                enableGlow = false
            )
        }
        if (this is ViewGroup && this !is RecyclerView) {
            for (child in children) {
                child.polishLiquidInteractiveTree()
            }
        }
    }
}
