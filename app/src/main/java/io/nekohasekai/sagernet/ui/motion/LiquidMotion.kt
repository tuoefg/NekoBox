// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.ui.motion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EdgeEffect
import androidx.core.view.children
import androidx.core.view.doOnPreDraw
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.theme.LiquidGlass
import io.nekohasekai.sagernet.ui.theme.LiquidGlass.applyLiquidCompactScrollbar
import io.nekohasekai.sagernet.ui.theme.LiquidPrefs
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign

/**
 * DIY Apple Liquid Glass 2.1 spring-physics jelly motion engine for Nekobox-Next.
 * - Touch-point-aware 3D micro-tilt + volume-preserving multi-axis jelly bounce + specular glow droplet
 * - Touch-centered radial pop-out spring morphs with diagonal glass sheen sweep & staggered inner choreography
 * - Refined half-height elastic wave overscroll & compact pill scrollbar
 */
object LiquidMotion {

    private val TAG_SPRING_X = R.id.liquid_tag_spring_x
    private val TAG_SPRING_Y = R.id.liquid_tag_spring_y
    private val TAG_SPRING_TX = R.id.liquid_tag_spring_tx
    private val TAG_SPRING_TY = R.id.liquid_tag_spring_ty
    private val TAG_SPRING_RX = R.id.liquid_tag_spring_rx
    private val TAG_SPRING_RY = R.id.liquid_tag_spring_ry
    private val TAG_SPRING_ROT = R.id.liquid_tag_spring_rot
    private val TAG_TOUCH_GLOW = R.id.liquid_tag_touch_glow
    private val TAG_JELLY_ATTACHED = R.id.liquid_tag_jelly_attached
    private val TAG_SCROLL_ATTACHED = R.id.liquid_tag_scroll_attached
    private val TAG_LEFT_ICON = R.id.liquid_tag_left_icon_target

    private fun jellyMode(): Int = if (LiquidGlass.isClassic()) 0 else LiquidPrefs.jellyLevel

    fun jellyIntensity(level: Int = jellyMode()): Float = when (level.coerceIn(0, 5)) {
        0 -> 0f
        1 -> 0.38f
        2 -> 0.62f
        3 -> 0.90f
        4 -> 1.15f
        else -> 1.40f
    }

    fun scrollEffectLevel(): Int = if (LiquidGlass.isClassic()) 0 else LiquidPrefs.scrollLevel

    fun scrollEffectIntensity(level: Int = scrollEffectLevel()): Float = when (level.coerceIn(0, 5)) {
        0 -> 0f
        1 -> 0.35f
        2 -> 0.60f
        3 -> 0.90f
        4 -> 1.18f
        else -> 1.45f
    }

    private fun allowTouchTilt(): Boolean = jellyMode() >= 2
    private fun allowTouchGlow(): Boolean = jellyMode() >= 3
    private fun allowGlassSheenSweep(): Boolean = jellyMode() >= 3

    fun isHapticsEnabled(): Boolean = !LiquidGlass.isClassic() && LiquidPrefs.haptics

    fun isScrollEffectEnabled(): Boolean = scrollEffectLevel() > 0

    private fun areSystemAnimatorsEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ValueAnimator.areAnimatorsEnabled() else true

    fun areAnimationsEnabled(): Boolean {
        if (jellyMode() == 0) return false
        return areSystemAnimatorsEnabled()
    }

    private fun areScrollAnimationsEnabled(): Boolean {
        if (scrollEffectLevel() == 0) return false
        return areSystemAnimatorsEnabled()
    }

    fun View.performSubtleHaptic() {
        if (!isHapticsEnabled()) return
        runCatching {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun View.springFor(
        tagId: Int,
        property: DynamicAnimation.ViewProperty,
        finalPos: Float,
        dampingRatio: Float,
        stiffness: Float,
    ): SpringAnimation {
        val existing = getTag(tagId) as? SpringAnimation
        if (existing != null) {
            existing.spring.finalPosition = finalPos
            existing.spring.dampingRatio = dampingRatio
            existing.spring.stiffness = stiffness
            return existing
        }
        return SpringAnimation(this, property, finalPos).apply {
            spring = SpringForce(finalPos).apply {
                this.dampingRatio = dampingRatio
                this.stiffness = stiffness
            }
            setTag(tagId, this)
        }
    }

    /**
     * Lightweight radial Liquid Glass specular droplet drawn on ViewOverlay directly
     * around the user's touch coordinate (never interferes with background/foreground).
     */
    private class LiquidTouchGlowDrawable(private val cornerRadiusPx: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clipPath = Path()
        private val rectF = RectF()
        private var cx = 0f
        private var cy = 0f
        private var radius = 0f
        private var glowAlpha = 0f
        private var activeAnimator: ValueAnimator? = null

        fun onPress(x: Float, y: Float, viewW: Int, viewH: Int) {
            setBounds(0, 0, viewW, viewH)
            cx = x
            cy = y
            val targetR = max(viewW, viewH) * 0.60f
            activeAnimator?.cancel()
            activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    radius = targetR * (0.35f + 0.65f * t)
                    glowAlpha = 0.40f * t
                    invalidateSelf()
                }
                start()
            }
        }

        fun onMove(x: Float, y: Float) {
            cx = x
            cy = y
            invalidateSelf()
        }

        fun onRelease(expand: Boolean) {
            activeAnimator?.cancel()
            val startR = radius.coerceAtLeast(bounds.width() * 0.25f)
            val endR = if (expand) max(bounds.width(), bounds.height()) * 1.12f else startR * 0.9f
            val startA = glowAlpha
            activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (expand) 260L else 160L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    radius = startR + (endR - startR) * t
                    glowAlpha = startA * (1f - t)
                    invalidateSelf()
                }
                start()
            }
        }

        fun reset() {
            activeAnimator?.cancel()
            glowAlpha = 0f
            radius = 0f
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (glowAlpha <= 0.005f || radius <= 1f || bounds.isEmpty) return
            rectF.set(bounds)
            val r = cornerRadiusPx.coerceAtMost(minOf(rectF.width(), rectF.height()) * 0.5f)
            clipPath.reset()
            clipPath.addRoundRect(rectF, r, r, Path.Direction.CW)
            val saved = canvas.save()
            canvas.clipPath(clipPath)
            val centerAlpha = (glowAlpha * 255f).toInt().coerceIn(0, 255)
            val midAlpha = (glowAlpha * 92f).toInt().coerceIn(0, 255)
            paint.shader = RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(
                    Color.argb(centerAlpha, 255, 255, 255),
                    Color.argb(midAlpha, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.restoreToCount(saved)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * Diagonal refractive light sheen sweep that glides across frosted glass panels
     * as a dialog or popup blooms into view, then automatically cleans itself up.
     */
    private class LiquidSheenSweepDrawable(private val cornerRadiusPx: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clipPath = Path()
        private val rectF = RectF()
        var progress = 0f
            set(value) {
                field = value
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty || progress <= 0f || progress >= 1f) return
            rectF.set(bounds)
            val r = cornerRadiusPx.coerceAtMost(minOf(rectF.width(), rectF.height()) * 0.5f)
            clipPath.reset()
            clipPath.addRoundRect(rectF, r, r, Path.Direction.CW)
            val saved = canvas.save()
            canvas.clipPath(clipPath)

            val w = rectF.width()
            val h = rectF.height()
            val bandW = max(w, h) * 0.42f
            val travel = w + h * 0.45f + bandW * 2f
            val x0 = -bandW + travel * progress
            val x1 = x0 + bandW
            val fade = (1f - abs(progress - 0.42f) * 1.45f).coerceIn(0f, 1f)
            val peakAlpha = (68 * fade).toInt().coerceIn(0, 80)
            paint.shader = LinearGradient(
                x0,
                0f,
                x1,
                h * 0.48f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(peakAlpha, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(rectF, paint)
            canvas.restoreToCount(saved)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** Plays a brief diagonal refractive glass light sweep across a newly opened glass surface. */
    fun View.playGlassSheenSweep(cornerRadiusDp: Float = 26f, durationMs: Long = 440L) {
        if (!areAnimationsEnabled() || !allowGlassSheenSweep()) return
        doOnPreDraw {
            if (width <= 2 || height <= 2) return@doOnPreDraw
            val density = resources.displayMetrics.density
            val sheen = LiquidSheenSweepDrawable(cornerRadiusDp * density)
            sheen.setBounds(0, 0, width, height)
            overlay.add(sheen)
            ValueAnimator.ofFloat(0.01f, 0.99f).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener { anim ->
                    sheen.progress = anim.animatedValue as Float
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        overlay.remove(sheen)
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        overlay.remove(sheen)
                    }
                })
                start()
            }
        }
    }

    /**
     * Playful independent spring bounce + damped tilt wiggle for left-side icons & icon badges.
     * Fires on row tap, switch toggle, and scroll pop-in so every icon feels alive and responsive.
     */
    fun View.playIconSpring(intensity: Float = 1f, tiltSign: Float = 1f) {
        if (!areAnimationsEnabled() || visibility != View.VISIBLE) return
        val k = (intensity * jellyIntensity()).coerceIn(0.35f, 1.5f)
        pivotX = width * 0.5f
        pivotY = height * 0.5f
        scaleX = (1f - 0.14f * k).coerceAtLeast(0.76f)
        scaleY = (1f + 0.12f * k).coerceAtMost(1.18f)
        rotation = -10f * k * tiltSign
        springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, 0.27f, 410f)
            .setStartVelocity(4.2f * k)
            .animateToFinalPosition(1f)
        springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, 0.25f, 340f)
            .setStartVelocity(-2.6f * k)
            .animateToFinalPosition(1f)
        springFor(TAG_SPRING_ROT, DynamicAnimation.ROTATION, 0f, 0.25f, 320f)
            .setStartVelocity(165f * k * tiltSign)
            .animateToFinalPosition(0f)
    }

    /** Associates a left-side icon view with a row so touch press/release & scroll automatically animate it. */
    fun View.bindLiquidLeftIcon(iconView: View?) {
        setTag(TAG_LEFT_ICON, iconView)
    }

    /**
     * Attaches touch-point-aware 3D micro-tilt, specular liquid glow droplet, left-icon spring
     * choreography, and noticeable volume-preserving multi-axis jelly bounce. Safe alongside click & swipe.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun View.attachJellyTouch(
        squashX: Float = 0.91f,
        squashY: Float = 0.87f,
        popVelocityX: Float = 3.4f,
        popVelocityY: Float = 4.2f,
        tiltDegrees: Float = 2.6f,
        cornerRadiusDp: Float = 22f,
        enableGlow: Boolean = true,
        leftIcon: View? = null,
    ) {
        if (LiquidGlass.isClassic()) return
        if (leftIcon != null) setTag(TAG_LEFT_ICON, leftIcon)
        if (getTag(TAG_JELLY_ATTACHED) == true) return
        setTag(TAG_JELLY_ATTACHED, true)

        val density = resources.displayMetrics.density
        var glow: LiquidTouchGlowDrawable? = null

        fun getOrCreateGlow(view: View): LiquidTouchGlowDrawable? {
            if (!enableGlow) return null
            glow?.let { return it }
            return LiquidTouchGlowDrawable(cornerRadiusDp * density).also {
                glow = it
                view.setTag(TAG_TOUCH_GLOW, it)
                view.overlay.add(it)
            }
        }

        setOnTouchListener { v, event ->
            if (!areAnimationsEnabled() || !v.isEnabled) return@setOnTouchListener false
            val mode = jellyMode()
            val intensity = jellyIntensity(mode)
            val effSquashX = (1f - (1f - squashX) * intensity).coerceIn(0.76f, 0.99f)
            val effSquashY = (1f - (1f - squashY) * intensity).coerceIn(0.72f, 0.99f)
            val effVelX = popVelocityX * intensity
            val effVelY = popVelocityY * intensity
            val effTilt = if (allowTouchTilt()) tiltDegrees * intensity else 0f
            val useGlow = allowTouchGlow()
            val animateIcon = mode >= 2
            val boundIcon = if (animateIcon) v.getTag(TAG_LEFT_ICON) as? View else null
            val releaseDampingX = if (mode == 1) 0.56f else 0.29f
            val releaseDampingY = if (mode == 1) 0.52f else 0.25f
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    LiquidOrigin.capturePoint(event.rawX, event.rawY)
                    if (v.width > 0 && v.height > 0) {
                        if (effTilt > 0f) {
                            v.cameraDistance = 8000f * density
                            v.pivotX = v.width * 0.5f
                            v.pivotY = v.height * 0.5f
                            val nx = ((event.x / v.width) - 0.5f) * 2f
                            val ny = ((event.y / v.height) - 0.5f) * 2f
                            v.springFor(TAG_SPRING_RY, DynamicAnimation.ROTATION_Y, 0f, 0.62f, 520f)
                                .animateToFinalPosition((nx * effTilt).coerceIn(-effTilt, effTilt))
                            v.springFor(TAG_SPRING_RX, DynamicAnimation.ROTATION_X, 0f, 0.62f, 520f)
                                .animateToFinalPosition((-ny * effTilt).coerceIn(-effTilt, effTilt))
                        }
                        if (useGlow) getOrCreateGlow(v)?.onPress(event.x, event.y, v.width, v.height)
                    }
                    v.springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, 0.62f, 600f)
                        .animateToFinalPosition(effSquashX)
                    v.springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, 0.58f, 560f)
                        .animateToFinalPosition(effSquashY)
                    if (boundIcon != null && boundIcon.visibility == View.VISIBLE) {
                        boundIcon.pivotX = boundIcon.width * 0.5f
                        boundIcon.pivotY = boundIcon.height * 0.5f
                        val iconScale = (1f - 0.14f * intensity).coerceIn(0.78f, 0.96f)
                        boundIcon.springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, 0.56f, 620f)
                            .animateToFinalPosition(iconScale)
                        boundIcon.springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, 0.52f, 560f)
                            .animateToFinalPosition(iconScale)
                        boundIcon.springFor(TAG_SPRING_ROT, DynamicAnimation.ROTATION, 0f, 0.55f, 520f)
                            .animateToFinalPosition(-8f * intensity)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (v.width > 0 && v.height > 0 && (effTilt > 0f || useGlow)) {
                        if (effTilt > 0f) {
                            val nx = ((event.x / v.width) - 0.5f) * 2f
                            val ny = ((event.y / v.height) - 0.5f) * 2f
                            v.springFor(TAG_SPRING_RY, DynamicAnimation.ROTATION_Y, 0f, 0.68f, 480f)
                                .animateToFinalPosition((nx * effTilt).coerceIn(-effTilt, effTilt))
                            v.springFor(TAG_SPRING_RX, DynamicAnimation.ROTATION_X, 0f, 0.68f, 480f)
                                .animateToFinalPosition((-ny * effTilt).coerceIn(-effTilt, effTilt))
                        }
                        if (useGlow) glow?.onMove(event.x, event.y)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    LiquidOrigin.capturePoint(event.rawX, event.rawY)
                    if (useGlow) glow?.onRelease(expand = true) else glow?.reset()
                    if (effTilt > 0f) {
                        v.springFor(TAG_SPRING_RY, DynamicAnimation.ROTATION_Y, 0f, 0.34f, 360f)
                            .animateToFinalPosition(0f)
                        v.springFor(TAG_SPRING_RX, DynamicAnimation.ROTATION_X, 0f, 0.34f, 360f)
                            .animateToFinalPosition(0f)
                    }
                    v.springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, releaseDampingX, 360f)
                        .setStartVelocity(effVelX)
                        .animateToFinalPosition(1f)
                    v.springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, releaseDampingY, 310f)
                        .setStartVelocity(effVelY)
                        .animateToFinalPosition(1f)
                    boundIcon?.playIconSpring(intensity = 1.05f, tiltSign = 1f)
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (useGlow) glow?.onRelease(expand = false) else glow?.reset()
                    if (effTilt > 0f) {
                        v.springFor(TAG_SPRING_RY, DynamicAnimation.ROTATION_Y, 0f, 0.55f, 380f)
                            .animateToFinalPosition(0f)
                        v.springFor(TAG_SPRING_RX, DynamicAnimation.ROTATION_X, 0f, 0.55f, 380f)
                            .animateToFinalPosition(0f)
                    }
                    v.springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, 0.45f, 380f)
                        .animateToFinalPosition(1f)
                    v.springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, 0.45f, 380f)
                        .animateToFinalPosition(1f)
                    if (boundIcon != null && boundIcon.visibility == View.VISIBLE) {
                        boundIcon.springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, 0.48f, 400f)
                            .animateToFinalPosition(1f)
                        boundIcon.springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, 0.48f, 400f)
                            .animateToFinalPosition(1f)
                        boundIcon.springFor(TAG_SPRING_ROT, DynamicAnimation.ROTATION, 0f, 0.48f, 380f)
                            .animateToFinalPosition(0f)
                    }
                }
            }
            false
        }
    }

    /** Plays a juicy, volume-preserving asymmetric jelly wobble pulse. */
    fun View.playJellyPop(intensity: Float = 1f, levelOverride: Int? = null) {
        val level = levelOverride ?: jellyMode()
        if (level <= 0 || !areSystemAnimatorsEnabled()) return
        val k = (intensity * jellyIntensity(level)).coerceIn(0.22f, 1.6f)
        scaleX = (1f - 0.10f * k).coerceAtLeast(0.80f)
        scaleY = (1f + 0.09f * k).coerceAtMost(1.16f)
        val dampX = if (level == 1) 0.56f else 0.27f
        val dampY = if (level == 1) 0.52f else 0.24f
        springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, dampX, 360f)
            .setStartVelocity(3.6f * k)
            .animateToFinalPosition(1f)
        springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, dampY, 300f)
            .setStartVelocity(-2.4f * k)
            .animateToFinalPosition(1f)
    }

    /**
     * Animates a dialog / popup / sheet expanding directly out of the user's exact
     * tap point with elastic jelly overshoot, diagonal glass sheen sweep, subtle haptic,
     * and staggered inner content choreography.
     */
    fun View.animatePopFromOrigin(anchor: View? = null, cornerRadiusDp: Float = 28f) {
        if (!areAnimationsEnabled()) return
        if (anchor != null) LiquidOrigin.capture(anchor)
        val origin = LiquidOrigin.peekRecentPoint()
        performSubtleHaptic()

        doOnPreDraw {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1f || h <= 1f) return@doOnPreDraw

            val mode = jellyMode()
            val k = jellyIntensity(mode)
            if (mode == 1) {
                // Ultra-light crisp pop for Level 1 (Light): zero heavy cascade/sheen overhead.
                pivotX = w * 0.5f
                pivotY = h * 0.5f
                translationX = 0f
                translationY = resources.displayMetrics.density * 8f
                scaleX = 0.92f
                scaleY = 0.92f
                alpha = 0f
                animate().alpha(1f).setDuration(110L).start()
                springFor(TAG_SPRING_TY, DynamicAnimation.TRANSLATION_Y, 0f, 0.78f, 500f)
                    .animateToFinalPosition(0f)
                springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, 0.68f, 520f)
                    .animateToFinalPosition(1f)
                springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, 0.68f, 520f)
                    .animateToFinalPosition(1f)
                return@doOnPreDraw
            }

            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val cx = loc[0] + w * 0.5f
            val cy = loc[1] + h * 0.5f

            if (origin != null) {
                pivotX = (origin.x - loc[0]).coerceIn(w * 0.08f, w * 0.92f)
                pivotY = (origin.y - loc[1]).coerceIn(h * 0.08f, h * 0.92f)
                val dx = (origin.x - cx).coerceIn(-w * 0.85f, w * 0.85f)
                val dy = (origin.y - cy).coerceIn(-h * 0.85f, h * 0.85f)
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val travelFactor = if (dist > 12f) (0.42f * k).coerceAtMost(0.58f) else 0f
                translationX = dx * travelFactor
                translationY = dy * travelFactor
            } else {
                pivotX = w * 0.5f
                pivotY = h * 0.5f
                translationX = 0f
                translationY = resources.displayMetrics.density * 14f
            }

            val startScaleX = (1f - 0.65f * k.coerceAtMost(1.1f)).coerceIn(0.22f, 0.78f)
            val startScaleY = (1f - 0.70f * k.coerceAtMost(1.1f)).coerceIn(0.16f, 0.74f)
            scaleX = startScaleX
            scaleY = startScaleY
            alpha = 0f

            animate().alpha(1f).setDuration(140L).start()
            springFor(TAG_SPRING_TX, DynamicAnimation.TRANSLATION_X, 0f, 0.60f, 360f)
                .animateToFinalPosition(0f)
            springFor(TAG_SPRING_TY, DynamicAnimation.TRANSLATION_Y, 0f, 0.56f, 340f)
                .animateToFinalPosition(0f)
            springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, (0.39f / k).coerceIn(0.28f, 0.62f), 340f)
                .setStartVelocity(2.0f * k)
                .animateToFinalPosition(1f)
            springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, (0.33f / k).coerceIn(0.24f, 0.58f), 280f)
                .setStartVelocity(2.3f * k)
                .animateToFinalPosition(1f)

            if (allowGlassSheenSweep()) {
                playGlassSheenSweep(cornerRadiusDp = cornerRadiusDp, durationMs = 460L)
            }
            if (mode >= 3) {
                cascadeInnerContent(this)
            }
        }
    }

    /** Subtly cascades direct dialog sections (title, body, button bar) with staggered micro-rise. */
    private fun cascadeInnerContent(root: View) {
        val density = root.resources.displayMetrics.density
        val parentPanel = root.findViewById<ViewGroup>(androidx.appcompat.R.id.parentPanel)
            ?: (root as? ViewGroup)?.let { vg ->
                if (vg.childCount == 1 && vg.getChildAt(0) is ViewGroup) vg.getChildAt(0) as ViewGroup else vg
            } ?: return

        var step = 0
        for (child in parentPanel.children) {
            if (child.visibility != View.VISIBLE) continue
            val delayMs = (step * 30L).coerceAtMost(120L)
            step++
            child.alpha = 0.25f
            child.translationY = density * 10f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(210L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    /**
     * Adds refined springy pop-in entrance animation, compact half-height scrollbar, AND
     * tighter progressive-wave elastic jelly overscroll to any RecyclerView.
     */
    fun RecyclerView.attachLiquidJellyScroll() {
        if (LiquidGlass.isClassic()) return
        if (getTag(TAG_SCROLL_ATTACHED) == true) return
        setTag(TAG_SCROLL_ATTACHED, true)
        clipToPadding = false
        clipChildren = false
        applyLiquidCompactScrollbar()

        var lastScrollDy = 0
        var isUserScrolling = false

        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                isUserScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    resetResidualOverscroll(recyclerView)
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) lastScrollDy = dy
            }
        })

        addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val level = scrollEffectLevel()
                // Level 0 = off; Level 1 (Light) skips per-item entrance springs for lag-free fast scrolling.
                if (level < 2 || !areScrollAnimationsEnabled()) return
                if (!isUserScrolling && lastScrollDy == 0) return
                val k = scrollEffectIntensity(level)
                val density = view.resources.displayMetrics.density
                val dir = if (lastScrollDy >= 0) 1f else -1f
                view.translationY = dir * density * (15f * k).coerceAtMost(24f)
                view.scaleX = (1f - 0.07f * k).coerceAtLeast(0.88f)
                view.scaleY = (1f - 0.05f * k).coerceAtLeast(0.90f)
                view.alpha = (1f - 0.22f * k).coerceAtLeast(0.66f)
                view.animate().alpha(1f).setDuration(155L).start()
                view.springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, 0.48f, 380f)
                    .setStartVelocity(1.4f * k).animateToFinalPosition(1f)
                view.springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, 0.44f, 340f)
                    .setStartVelocity(1.6f * k).animateToFinalPosition(1f)
                view.springFor(TAG_SPRING_TY, DynamicAnimation.TRANSLATION_Y, 0f, 0.56f, 350f)
                    .animateToFinalPosition(0f)
                if (level >= 3) {
                    (view.getTag(TAG_LEFT_ICON) as? View)?.let { icon ->
                        if (icon.visibility == View.VISIBLE) {
                            icon.scaleX = (1f - 0.22f * k).coerceAtLeast(0.70f)
                            icon.scaleY = (1f - 0.22f * k).coerceAtLeast(0.70f)
                            icon.rotation = (if (dir > 0f) -7f else 7f) * k.coerceAtMost(1.3f)
                            icon.springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, 0.36f, 360f)
                                .setStartVelocity(2.2f * k).animateToFinalPosition(1f)
                            icon.springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, 0.36f, 360f)
                                .setStartVelocity(2.2f * k).animateToFinalPosition(1f)
                            icon.springFor(TAG_SPRING_ROT, DynamicAnimation.ROTATION, 0f, 0.34f, 310f)
                                .animateToFinalPosition(0f)
                        }
                    }
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                view.animate().cancel()
                (view.getTag(TAG_SPRING_X) as? SpringAnimation)?.cancel()
                (view.getTag(TAG_SPRING_Y) as? SpringAnimation)?.cancel()
                (view.getTag(TAG_SPRING_TX) as? SpringAnimation)?.cancel()
                (view.getTag(TAG_SPRING_TY) as? SpringAnimation)?.cancel()
                (view.getTag(TAG_SPRING_RX) as? SpringAnimation)?.cancel()
                (view.getTag(TAG_SPRING_RY) as? SpringAnimation)?.cancel()
                (view.getTag(TAG_SPRING_ROT) as? SpringAnimation)?.cancel()
                (view.getTag(TAG_TOUCH_GLOW) as? LiquidTouchGlowDrawable)?.reset()
                (view.getTag(TAG_LEFT_ICON) as? View)?.let { icon ->
                    (icon.getTag(TAG_SPRING_X) as? SpringAnimation)?.cancel()
                    (icon.getTag(TAG_SPRING_Y) as? SpringAnimation)?.cancel()
                    (icon.getTag(TAG_SPRING_ROT) as? SpringAnimation)?.cancel()
                    icon.scaleX = 1f
                    icon.scaleY = 1f
                    icon.rotation = 0f
                }
                view.translationX = 0f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                view.rotationX = 0f
                view.rotationY = 0f
                view.rotation = 0f
                view.alpha = 1f
            }
        })

        edgeEffectFactory = JellyEdgeEffectFactory()
    }

    private fun resetResidualOverscroll(recyclerView: RecyclerView) {
        val count = recyclerView.childCount
        for (i in 0 until count) {
            val child = recyclerView.getChildAt(i)
            val shifted = abs(child.translationY) > 0.5f ||
                abs(child.scaleY - 1f) > 0.005f ||
                abs(child.scaleX - 1f) > 0.005f
            if (!shifted) continue
            if (areScrollAnimationsEnabled()) {
                child.springFor(TAG_SPRING_TY, DynamicAnimation.TRANSLATION_Y, 0f, 0.48f, 320f)
                    .animateToFinalPosition(0f)
                child.springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, 0.42f, 340f)
                    .animateToFinalPosition(1f)
                child.springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, 0.44f, 350f)
                    .animateToFinalPosition(1f)
            } else {
                child.translationY = 0f
                child.scaleX = 1f
                child.scaleY = 1f
            }
        }
    }

    /** Controlled progressive-wave elastic jelly overscroll scaled by 5-level intensity. */
    private class JellyEdgeEffectFactory : RecyclerView.EdgeEffectFactory() {
        override fun createEdgeEffect(recyclerView: RecyclerView, direction: Int): EdgeEffect {
            return object : EdgeEffect(recyclerView.context) {
                // Keep platform EdgeEffect distance at 0 and state idle so Android 12+
                // RecyclerView.stopGlowAnimations() never intercepts subsequent item clicks
                // when draw() returns false for our custom spring-based wave overscroll.
                override fun getDistance(): Float = 0f

                override fun isFinished(): Boolean = true

                override fun finish() {
                    springBack()
                }

                override fun onPull(deltaDistance: Float) {
                    handlePull(deltaDistance)
                }

                override fun onPull(deltaDistance: Float, displacement: Float) {
                    handlePull(deltaDistance)
                }

                override fun onPullDistance(deltaDistance: Float, displacement: Float): Float {
                    handlePull(deltaDistance)
                    return 0f
                }

                private fun handlePull(deltaDistance: Float) {
                    if (!areScrollAnimationsEnabled()) return
                    if (direction != DIRECTION_TOP && direction != DIRECTION_BOTTOM) return
                    val k = scrollEffectIntensity()
                    val h = recyclerView.height.toFloat().coerceAtLeast(1f)
                    val sign = if (direction == DIRECTION_BOTTOM) -1f else 1f
                    val maxShift = h * (0.082f * k).coerceAtMost(0.135f)
                    val baseDelta = sign * h * deltaDistance * (0.24f * k)
                    val count = recyclerView.childCount
                    for (i in 0 until count) {
                        val child = recyclerView.getChildAt(i)
                        val distRatio = if (direction == DIRECTION_TOP) {
                            (child.top / h).coerceIn(0f, 1f)
                        } else {
                            ((h - child.bottom) / h).coerceIn(0f, 1f)
                        }
                        val waveWeight = 1.08f - distRatio * 0.56f
                        val nextY = (child.translationY + baseDelta * waveWeight).coerceIn(-maxShift, maxShift)
                        child.translationY = nextY
                        val stretch = (abs(nextY) / maxShift.coerceAtLeast(1f)) * (0.036f * k) * waveWeight
                        child.scaleY = 1f + stretch
                        child.scaleX = 1f - stretch * 0.40f
                    }
                }

                override fun onRelease() {
                    springBack()
                }

                override fun onAbsorb(velocity: Int) {
                    if (!areScrollAnimationsEnabled()) return
                    if (direction != DIRECTION_TOP && direction != DIRECTION_BOTTOM) return
                    val k = scrollEffectIntensity()
                    val sign = if (direction == DIRECTION_BOTTOM) -1f else 1f
                    val maxImpulse = 1150f * k
                    val impulse = sign * (velocity * 0.24f * k).coerceIn(-maxImpulse, maxImpulse)
                    val count = recyclerView.childCount
                    val dampY = if (scrollEffectLevel() == 1) 0.62f else 0.42f
                    for (i in 0 until count) {
                        val child = recyclerView.getChildAt(i)
                        val rank = if (direction == DIRECTION_TOP) i else (count - 1 - i)
                        val waveStiffness = (300f - rank * 12f).coerceAtLeast(200f)
                        child.springFor(TAG_SPRING_TY, DynamicAnimation.TRANSLATION_Y, 0f, dampY, waveStiffness)
                            .setStartVelocity(impulse * (1f - rank * 0.06f).coerceAtLeast(0.45f))
                            .animateToFinalPosition(0f)
                        child.springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, dampY, waveStiffness + 20f)
                            .setStartVelocity(sign(impulse) * 0.65f * k)
                            .animateToFinalPosition(1f)
                        child.springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, dampY, waveStiffness + 30f)
                            .animateToFinalPosition(1f)
                    }
                }

                private fun springBack() {
                    if (!areScrollAnimationsEnabled()) {
                        resetResidualOverscroll(recyclerView)
                        return
                    }
                    val damp = if (scrollEffectLevel() == 1) 0.64f else 0.44f
                    val count = recyclerView.childCount
                    for (i in 0 until count) {
                        val child = recyclerView.getChildAt(i)
                        val rank = if (direction == DIRECTION_TOP) i else (count - 1 - i)
                        val waveStiffness = (320f - rank * 12f).coerceAtLeast(210f)
                        child.springFor(TAG_SPRING_TY, DynamicAnimation.TRANSLATION_Y, 0f, damp, waveStiffness)
                            .animateToFinalPosition(0f)
                        child.springFor(TAG_SPRING_Y, DynamicAnimation.SCALE_Y, 1f, damp, waveStiffness + 15f)
                            .animateToFinalPosition(1f)
                        child.springFor(TAG_SPRING_X, DynamicAnimation.SCALE_X, 1f, damp, waveStiffness + 25f)
                            .animateToFinalPosition(1f)
                    }
                }

                override fun draw(canvas: android.graphics.Canvas?): Boolean = false
            }
        }
    }

    /** Fluid elastic depth & scale page transformer for ViewPager2. */
    fun ViewPager2.attachLiquidPageTransformer() {
        setPageTransformer { page, position ->
            if (!areAnimationsEnabled()) {
                page.alpha = 1f
                page.scaleX = 1f
                page.scaleY = 1f
                page.translationX = 0f
                return@setPageTransformer
            }
            val k = jellyIntensity().coerceAtMost(1.1f)
            val absPos = abs(position).coerceAtMost(1f)
            page.scaleX = 1f - absPos * 0.055f * k
            page.scaleY = 1f - absPos * 0.035f * k
            page.alpha = 1f - absPos * 0.24f * k
            page.translationX = -position * page.width * 0.045f * k
        }
    }

    /** Attaches juicy jelly tap & selection pop animations to every tab in a TabLayout. */
    fun TabLayout.attachLiquidTabPop() {
        val strip = getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until strip.childCount) {
            strip.getChildAt(i).attachJellyTouch(
                squashX = 0.89f,
                squashY = 0.84f,
                popVelocityX = 3.2f,
                popVelocityY = 3.8f,
                tiltDegrees = 1.8f,
                cornerRadiusDp = 999f
            )
        }
        addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tab.view.attachJellyTouch(
                    squashX = 0.89f,
                    squashY = 0.84f,
                    popVelocityX = 3.2f,
                    popVelocityY = 3.8f,
                    tiltDegrees = 1.8f,
                    cornerRadiusDp = 999f
                )
                tab.view.playJellyPop(0.95f)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                tab.view.playJellyPop(0.85f)
            }
        })
    }
}
