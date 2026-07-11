package app.lawnchair.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import com.android.launcher3.CellLayout
import com.android.launcher3.Launcher
import com.android.launcher3.Workspace
import com.android.launcher3.anim.SpringAnimationBuilder
import com.android.launcher3.celllayout.CellLayoutLayoutParams

private val springFast = PathInterpolator(0.34f, 1.4f, 0.64f, 1.0f)
private val easeOutBack = OvershootInterpolator(2.0f)
private val quickEase = PathInterpolator(0.33f, 0f, 0.2f, 1f)

/** Opacity ramp: a smooth, visible fade-in. Gentle at both ends so the icon washes in. */
private val dropFade = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

/**
 * One continuous spring runs the whole drop, like the folder close (FolderSpringAnimatorSet uses
 * SpringAnimationBuilder). The overshoot and the return are two halves of one solution, so velocity
 * never jumps. Softer position stiffness stretches the visible glide so the weight is felt; scale
 * is kept stiffer so an icon resolves close to full size just before it nudges into place.
 */
private const val SPRING_STIFFNESS_POSITION = 70f
private const val SPRING_STIFFNESS_SCALE = 130f
private const val SPRING_DAMPING_POSITION = 0.5f
private const val SPRING_DAMPING_SCALE = 0.78f

/** Resolution at which each spring is considered at rest. Sets how long it rings. */
private const val MIN_VISIBLE_CHANGE = 1f / 500f

private const val ROW_DELAY_MS = 70L
private const val ALPHA_DURATION_MS = 210L

/** Negative: the icon comes down from above, so the spring overshoot carries it below the slot. */
private const val START_OFFSET_DP = -40f

/** Starts enlarged, as if recessed toward the glass, but moderate so it barely overlaps neighbours. */
private const val START_SCALE = 2.6f

private const val PULSE_START_RADIUS_DP = 40f
private const val PULSE_END_RADIUS_DP = 200f
private const val PULSE_ALPHA = 0.6f
private const val PULSE_DURATION = 100L

private const val SHIMMER_DELAY = 750L
private const val SHIMMER_DURATION = 150L

/** How long after the drop is due to finish the clip/order watchdog fires. */
private const val CLIP_WATCHDOG_SLACK_MS = 750L

class WorkspaceAnimator(private val launcher: Launcher) {

    private var animating = false
    private var screenOffAnimator: AnimatorSet? = null
    private var unlockAnimator: AnimatorSet? = null
    private var restoreClip: (() -> Unit)? = null
    private var screenIsOff = false

    /** Last-resort restore of the clip flags and child order; idempotent. */
    private val clipWatchdog = Runnable { restoreClipNow() }

    private fun restoreClipNow() {
        launcher.dragLayer?.removeCallbacks(clipWatchdog)
        restoreClip?.invoke()
        restoreClip = null
    }

    /**
     * Both springs are normalised 0 -> 1 and solved once, not per icon. A spring's shape does not
     * depend on how far it travels, so every icon reads the same curve and scales it to its own
     * distance.
     */
    private val positionSpring: SpringAnimationBuilder by lazy {
        SpringAnimationBuilder(launcher)
            .setStartValue(0f)
            .setEndValue(1f)
            .setStiffness(SPRING_STIFFNESS_POSITION)
            .setDampingRatio(SPRING_DAMPING_POSITION)
            .setMinimumVisibleChange(MIN_VISIBLE_CHANGE)
            .computeParams()
    }

    private val scaleSpring: SpringAnimationBuilder by lazy {
        SpringAnimationBuilder(launcher)
            .setStartValue(0f)
            .setEndValue(1f)
            .setStiffness(SPRING_STIFFNESS_SCALE)
            .setDampingRatio(SPRING_DAMPING_SCALE)
            .setMinimumVisibleChange(MIN_VISIBLE_CHANGE)
            .computeParams()
    }

    private fun getWorkspace(): Workspace<*> = launcher.getWorkspace()

    private fun dp(value: Float): Float =
        value * launcher.resources.displayMetrics.density

    private fun getAllIconsOnPage(page: CellLayout): List<View> {
        val icons = mutableListOf<View>()
        val container = page.shortcutsAndWidgets ?: return icons
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child != null && child.visibility == View.VISIBLE) {
                icons.add(child)
            }
        }
        return icons
    }

    fun getAllWorkspaceIcons(): List<View> {
        val icons = mutableListOf<View>()
        val workspace = getWorkspace()
        for (i in 0 until workspace.pageCount) {
            val page = workspace.getPageAt(i) as? CellLayout ?: continue
            icons.addAll(getAllIconsOnPage(page))
        }
        val hotseat = launcher.hotseat
        if (hotseat != null) {
            icons.addAll(getAllIconsOnPage(hotseat))
        }
        return icons
    }

    private fun getIconRow(icon: View): Int {
        val lp = icon.layoutParams
        return if (lp is CellLayoutLayoutParams) lp.cellY else 0
    }

    fun getAllWidgets(): List<View> {
        val widgets = mutableListOf<View>()
        val workspace = getWorkspace()
        for (i in 0 until workspace.pageCount) {
            val page = workspace.getPageAt(i) as? CellLayout ?: continue
            val container = page.shortcutsAndWidgets ?: continue
            for (j in 0 until container.childCount) {
                val child = container.getChildAt(j)
                if (child != null &&
                    (child is android.appwidget.AppWidgetHostView ||
                        child.javaClass.name.contains("AppWidgetHostView") ||
                        child.javaClass.name.contains("LauncherAppWidgetHostView"))
                ) {
                    widgets.add(child)
                }
            }
        }
        return widgets
    }

    /**
     * Every icon on every page and the hotseat, including ones currently INVISIBLE. A reset must
     * see hidden icons: the unlock hides each icon until its row is due, so a cancelled unlock
     * leaves rows at INVISIBLE that a visibility-filtered collector would skip forever.
     */
    private fun forEachIconIncludingHidden(action: (View) -> Unit) {
        val workspace = getWorkspace()
        val pages = (0 until workspace.pageCount).mapNotNull { workspace.getPageAt(it) as? CellLayout }
        for (page in pages + listOfNotNull(launcher.hotseat)) {
            val container = page.shortcutsAndWidgets ?: continue
            for (i in 0 until container.childCount) {
                container.getChildAt(i)?.let(action)
            }
        }
    }

    fun animateScreenOff(animDuration: Long = 300L) {
        screenOffAnimator?.cancel()

        unlockAnimator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        unlockAnimator = null
        restoreClipNow()
        forEachIconIncludingHidden(::resetIcon)

        screenIsOff = true

        val icons = getAllWorkspaceIcons()
        val widgets = getAllWidgets()
        val workspace = getWorkspace()

        val set = AnimatorSet()
        val animators = mutableListOf<Animator>()

        for ((index, icon) in icons.withIndex()) {
            val delay = index * 12L
            animators.add(
                ObjectAnimator.ofPropertyValuesHolder(
                    icon,
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0f),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.4f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.4f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 20f),
                ).apply {
                    duration = animDuration
                    startDelay = delay
                    interpolator = quickEase
                },
            )
        }
        for (widget in widgets) {
            animators.add(
                ObjectAnimator.ofFloat(widget, View.ALPHA, 0f).apply {
                    duration = animDuration / 2
                    interpolator = quickEase
                },
            )
        }

        set.playTogether(animators)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (screenIsOff) workspace.visibility = View.INVISIBLE
                animating = false
                screenOffAnimator = null
            }
        })
        animating = true
        screenOffAnimator = set
        set.start()
    }

    /**
     * Drop-forward unlock: icons come down from above, enlarged and pulled apart by roughly one
     * icon, fade in, and spring to their slots on a single continuous spring with a weighty
     * overshoot. Row by row from the top; the dock rides the tail.
     */
    fun animateUnlock(onEnd: Runnable? = null) {
        // onUserPresent and onResume both fire on the way back from the keyguard. Let a running
        // drop finish rather than restart it mid-flight.
        if (unlockAnimator?.isRunning == true) return

        screenIsOff = false
        screenOffAnimator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        screenOffAnimator = null
        restoreClipNow()
        animating = false

        val workspace = getWorkspace()
        val widgets = getAllWidgets()

        workspace.visibility = View.VISIBLE
        // Restore every icon — hidden ones included — before collecting what to animate.
        forEachIconIncludingHidden {
            it.animate().cancel()
            resetIcon(it)
        }
        for (widget in widgets) widget.alpha = 1f

        val page = workspace.getPageAt(workspace.currentPage) as? CellLayout
        val container = page?.shortcutsAndWidgets
        val hotseatIcons = launcher.hotseat?.let { getAllIconsOnPage(it) } ?: emptyList()
        val gridIcons = page?.let { getAllIconsOnPage(it) } ?: emptyList()

        if (page == null || container == null || container.width == 0 ||
            (gridIcons.isEmpty() && hotseatIcons.isEmpty())
        ) {
            animating = false
            onEnd?.run()
            return
        }

        val rows = page.countY
        val cols = page.countX
        val iconGap = launcher.deviceProfile.iconSizePx.toFloat()
        val centerCol = (cols - 1) / 2f
        val centerRow = (rows - 1) / 2f

        val animators = mutableListOf<Animator>()

        for (icon in gridIcons) {
            val lp = icon.layoutParams as? CellLayoutLayoutParams
            val row = lp?.cellY ?: 0
            val col = lp?.cellX ?: 0
            addDropAnimators(
                icon, animators,
                delay = row * ROW_DELAY_MS,
                spreadX = (col - centerCol) * iconGap,
                spreadY = (row - centerRow) * iconGap,
                startPx = dp(START_OFFSET_DP),
            )
        }

        for (icon in hotseatIcons) {
            val lp = icon.layoutParams as? CellLayoutLayoutParams
            val col = lp?.cellX ?: 0
            addDropAnimators(
                icon, animators,
                delay = rows * ROW_DELAY_MS,
                spreadX = (col - centerCol) * iconGap,
                spreadY = 0f,
                startPx = dp(START_OFFSET_DP),
            )
        }

        for ((index, widget) in widgets.withIndex()) {
            widget.alpha = 0f
            animators.add(
                ObjectAnimator.ofFloat(widget, View.ALPHA, 1f).apply {
                    duration = 500L
                    startDelay = rows * ROW_DELAY_MS + index * 30L
                    interpolator = dropFade
                },
            )
        }

        buildPulseAnimator()?.let { animators.add(it) }
        animators.add(buildShimmerAnimator(gridIcons + hotseatIcons))

        val unclip = unclipForFlight(
            *ancestorsOf(container).toTypedArray(),
            *ancestorsOf(launcher.hotseat?.shortcutsAndWidgets).toTypedArray(),
        )
        val reveal = elevateForFlight()
        restoreClip = { unclip(); reveal() }

        val set = AnimatorSet()
        set.playTogether(animators)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                unlockAnimator = null
                restoreClipNow()
                workspace.visibility = View.VISIBLE
                forEachIconIncludingHidden(::resetIcon)
                for (widget in widgets) widget.alpha = 1f
                animating = false
                onEnd?.run()
            }
        })
        animating = true
        unlockAnimator = set
        set.start()

        val watchdogAt = set.totalDuration.let { if (it < 0) 3_000L else it + CLIP_WATCHDOG_SLACK_MS }
        launcher.dragLayer?.postDelayed(clipWatchdog, watchdogAt)
    }

    /**
     * One icon's drop: it arrives from behind the screen edge oversized and pulled out from the
     * centre, overshoots its slot, and settles. Scale and translation ride one animator so the
     * overshoot and the ring down stay in phase; each samples its own spring.
     */
    private fun addDropAnimators(
        icon: View,
        out: MutableList<Animator>,
        delay: Long,
        spreadX: Float,
        spreadY: Float,
        startPx: Float,
    ) {
        val startY = startPx + spreadY

        icon.translationX = spreadX
        icon.translationY = startY
        icon.scaleX = START_SCALE
        icon.scaleY = START_SCALE
        icon.alpha = 0f
        icon.visibility = View.INVISIBLE

        out.add(
            ObjectAnimator.ofFloat(icon, View.ALPHA, 1f).apply {
                duration = ALPHA_DURATION_MS
                startDelay = delay
                interpolator = dropFade
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        icon.visibility = View.VISIBLE
                    }
                })
            },
        )

        val posDuration = positionSpring.duration.toFloat()
        val scaleDuration = scaleSpring.duration.toFloat()
        val total = maxOf(positionSpring.duration, scaleSpring.duration)

        out.add(
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = total
                startDelay = delay
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val elapsed = (anim.animatedValue as Float) * total
                    val sPos = springAt(positionSpring, elapsed, posDuration)
                    icon.translationY = lerp(startY, 0f, sPos)
                    icon.translationX = lerp(spreadX, 0f, sPos)
                    val s = lerp(START_SCALE, 1f, springAt(scaleSpring, elapsed, scaleDuration))
                    icon.scaleX = s
                    icon.scaleY = s
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        icon.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        icon.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                })
            },
        )
    }

    /** Samples a spring at [elapsedMs]; pinned to 1 once past its own duration so it cannot drift. */
    private fun springAt(spring: SpringAnimationBuilder, elapsedMs: Float, durationMs: Float): Float =
        if (elapsedMs >= durationMs) 1f else spring.getInterpolatedValue(elapsedMs / durationMs)

    /**
     * Reorders the workspace and hotseat above the DragLayer siblings that otherwise paint over
     * them — the page-indicator dots, the smartspace, the scrim. DragLayer draws its children in a
     * fixed custom order (getChildDrawingOrder), so translationZ does nothing; moving the actual
     * children is the only lever. The original order is captured and replayed on restore.
     */
    private fun elevateForFlight(): () -> Unit {
        val dragLayer = launcher.dragLayer ?: return {}
        val original = (0 until dragLayer.childCount).map { dragLayer.getChildAt(it) }

        getWorkspace().bringToFront()
        launcher.hotseat?.bringToFront()
        dragLayer.invalidate()

        return {
            // Replaying bringToFront over the children in their original order restores that order:
            // each call moves one child to the end, so the last to move ends up last.
            original.forEach { it.bringToFront() }
            dragLayer.invalidate()
        }
    }

    /** [view] and every ViewGroup above it, up to and including the LauncherRootView. */
    private fun ancestorsOf(view: View?): List<ViewGroup> {
        val chain = mutableListOf<ViewGroup>()
        var node: View? = view
        val root = launcher.rootView
        while (node != null) {
            if (node is ViewGroup) chain.add(node)
            if (node === root) break
            node = node.parent as? View
        }
        return chain
    }

    private fun unclipForFlight(vararg groups: ViewGroup?): () -> Unit {
        // Snapshots the current flags, so it must never run over already-cleared ones. Self-heal
        // any previous unclip first.
        restoreClipNow()

        val saved = groups.filterNotNull()
            .distinctBy { System.identityHashCode(it) }
            .map { group ->
                val clipChildren = group.clipChildren
                val clipToPadding = group.clipToPadding
                group.clipChildren = false
                group.clipToPadding = false
                Triple(group, clipChildren, clipToPadding)
            }
        return {
            for ((group, clipChildren, clipToPadding) in saved) {
                group.clipChildren = clipChildren
                group.clipToPadding = clipToPadding
            }
        }
    }

    /**
     * A radial flash at the centre — the visual "break" the icons come through. Drawn into the
     * DragLayer overlay so it never disturbs the view hierarchy.
     */
    private fun buildPulseAnimator(): Animator? {
        val dragLayer = launcher.dragLayer ?: return null
        if (dragLayer.width == 0 || dragLayer.height == 0) return null

        val startR = dp(PULSE_START_RADIUS_DP)
        val endR = dp(PULSE_END_RADIUS_DP)

        val pulse = PulseDrawable(endR)
        pulse.setBounds(0, 0, dragLayer.width, dragLayer.height)
        dragLayer.overlay.add(pulse)

        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_DURATION
            addUpdateListener {
                val p = it.animatedValue as Float
                pulse.update(lerp(startR, endR, p), PULSE_ALPHA * (1f - p))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dragLayer.overlay.remove(pulse)
                }
            })
        }
    }

    /** A soft radial flash centred in its bounds; the gradient is built once and scaled. */
    private class PulseDrawable(private val unitRadius: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var radius = 0f
        private var shader: RadialGradient? = null

        fun update(radius: Float, intensity: Float) {
            this.radius = radius
            paint.alpha = (intensity * 255).toInt().coerceIn(0, 255)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (radius <= 0f || paint.alpha == 0 || unitRadius <= 0f) return
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val gradient = shader ?: RadialGradient(
                cx, cy, unitRadius,
                intArrayOf(Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            ).also { shader = it; paint.shader = it }
            @Suppress("UNUSED_EXPRESSION") gradient
            val scale = radius / unitRadius
            canvas.save()
            canvas.scale(scale, scale, cx, cy)
            canvas.drawCircle(cx, cy, unitRadius, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** The grid breathes once to signal everything has come to rest. */
    private fun buildShimmerAnimator(icons: List<View>): Animator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SHIMMER_DURATION
            startDelay = SHIMMER_DELAY
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                val a = 1f - 0.02f * (1f - Math.abs(2f * p - 1f))
                for (icon in icons) icon.alpha = a
            }
        }

    private fun resetIcon(icon: View) {
        icon.alpha = 1f
        icon.scaleX = 1f
        icon.scaleY = 1f
        icon.translationX = 0f
        icon.translationY = 0f
        icon.translationZ = 0f
        icon.visibility = View.VISIBLE
        icon.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    fun resetAllIcons() {
        // A running unlock owns the icons; its own end listener restores everything.
        if (unlockAnimator?.isRunning == true) return

        screenIsOff = false
        screenOffAnimator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        screenOffAnimator = null
        unlockAnimator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        unlockAnimator = null
        restoreClipNow()
        getWorkspace().visibility = View.VISIBLE
        forEachIconIncludingHidden(::resetIcon)
        for (widget in getAllWidgets()) widget.alpha = 1f
    }

    private fun lerp(from: Float, to: Float, p: Float): Float = from + (to - from) * p

    fun animateShadeOpened() {
        val icons = getAllWorkspaceIcons()
        if (icons.isEmpty()) return

        val set = AnimatorSet()
        val animators = mutableListOf<Animator>()

        for ((index, icon) in icons.withIndex()) {
            icon.alpha = 0.3f
            icon.scaleX = 0.75f
            icon.scaleY = 0.75f
            icon.translationY = 0f

            val delay = index * 8L
            animators.add(
                ObjectAnimator.ofFloat(icon, View.ALPHA, 0.3f, 0.7f).apply {
                    duration = 280L
                    startDelay = delay
                    interpolator = springFast
                },
            )
            animators.add(
                ObjectAnimator.ofPropertyValuesHolder(
                    icon,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.75f, 0.88f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.75f, 0.88f),
                ).apply {
                    duration = 280L
                    startDelay = delay
                    interpolator = springFast
                },
            )
        }

        set.playTogether(animators)
        set.start()
    }

    fun animateShadeClosed() {
        val icons = getAllWorkspaceIcons()
        if (icons.isEmpty()) return

        val iconsWithRow = icons.map { icon -> icon to getIconRow(icon) }
        val maxRow = iconsWithRow.maxOf { it.second }
        val grouped = iconsWithRow.groupBy { it.second }
        val sortedRows = grouped.entries.sortedByDescending { it.key }

        val set = AnimatorSet()
        val animators = mutableListOf<Animator>()

        var globalIndex = 0
        for ((row, rowIcons) in sortedRows) {
            val baseDelay = (maxRow - row) * 20L
            for ((icon, _) in rowIcons) {
                icon.alpha = 0.7f
                icon.scaleX = 0.88f
                icon.scaleY = 0.88f
                icon.translationY = 25f

                val delay = baseDelay + (globalIndex % 3) * 8L
                animators.add(
                    ObjectAnimator.ofPropertyValuesHolder(
                        icon,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f),
                    ).apply {
                        duration = 300L
                        startDelay = delay
                        interpolator = springFast
                    },
                )
                globalIndex++
            }
        }

        set.playTogether(animators)
        set.start()
    }

    fun animateSpringBack(target: View) {
        val set = AnimatorSet()
        val scaleAnim = ObjectAnimator.ofPropertyValuesHolder(
            target,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.07f, 1.0f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.07f, 1.0f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, -12f, 0f),
        )
        scaleAnim.duration = 400L
        scaleAnim.interpolator = springFast
        set.play(scaleAnim)
        set.start()
    }

    fun isAnimating(): Boolean = animating
}
