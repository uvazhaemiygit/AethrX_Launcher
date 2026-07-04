package app.lawnchair.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import com.android.launcher3.CellLayout
import com.android.launcher3.Launcher
import com.android.launcher3.Workspace
import com.android.launcher3.celllayout.CellLayoutLayoutParams

private val springFast = PathInterpolator(0.34f, 1.4f, 0.64f, 1.0f)
private val easeOutBack = OvershootInterpolator(2.0f)

class WorkspaceAnimator(private val launcher: Launcher) {

    private var animating = false

    private fun getWorkspace(): Workspace<*> = launcher.getWorkspace()

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
                     child.javaClass.name.contains("LauncherAppWidgetHostView"))) {
                    widgets.add(child)
                }
            }
        }
        return widgets
    }

    fun animateScreenOff(animDuration: Long = 300L) {
        val icons = getAllWorkspaceIcons()
        val widgets = getAllWidgets()
        val workspace = getWorkspace()

        val set = AnimatorSet()
        val animators = mutableListOf<Animator>()

        for ((index, icon) in icons.withIndex()) {
            val delay = index * 12L
            val anim = ObjectAnimator.ofPropertyValuesHolder(icon,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.4f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.4f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 20f)
            ).apply {
                this.duration = animDuration
                startDelay = delay
                interpolator = springFast
            }
            animators.add(anim)
        }

        for (widget in widgets) {
            val anim = ObjectAnimator.ofPropertyValuesHolder(widget,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f)
            ).apply {
                duration = animDuration / 2
                interpolator = springFast
            }
            animators.add(anim)
        }

        set.playTogether(animators)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                workspace.visibility = View.INVISIBLE
                animating = false
            }
        })
        animating = true
        set.start()
    }

    fun animateUnlock(onEnd: Runnable? = null) {
        val workspace = getWorkspace()
        val icons = getAllWorkspaceIcons()
        val widgets = getAllWidgets()

        workspace.visibility = View.VISIBLE

        for (icon in icons) {
            icon.alpha = 0f
            icon.scaleX = 0.5f
            icon.scaleY = 0.5f
            icon.translationY = 80f
        }
        for (widget in widgets) {
            widget.alpha = 0f
        }

        val set = AnimatorSet()
        val animators = mutableListOf<Animator>()

        for ((index, icon) in icons.withIndex()) {
            val delay = index * 18L
            val anim = ObjectAnimator.ofPropertyValuesHolder(icon,
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f)
            ).apply {
                this.duration = 500L
                startDelay = delay
                interpolator = easeOutBack
            }
            animators.add(anim)
        }

        for ((index, widget) in widgets.withIndex()) {
            val delay = 200L + index * 30L
            val anim = ObjectAnimator.ofFloat(widget, View.ALPHA, 1f).apply {
                duration = 400L
                startDelay = delay
                interpolator = springFast
            }
            animators.add(anim)
        }

        set.playTogether(animators)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                for (icon in icons) {
                    icon.alpha = 1f
                    icon.scaleX = 1f
                    icon.scaleY = 1f
                    icon.translationY = 0f
                }
                for (widget in widgets) {
                    widget.alpha = 1f
                }
                animating = false
                onEnd?.run()
            }
        })
        animating = true
        set.start()
    }

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
            val anim = ObjectAnimator.ofFloat(icon, View.ALPHA, 0.3f, 0.7f).apply {
                duration = 280L
                startDelay = delay
                interpolator = springFast
            }
            val scaleAnim = ObjectAnimator.ofPropertyValuesHolder(icon,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.75f, 0.88f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.75f, 0.88f)
            ).apply {
                duration = 280L
                startDelay = delay
                interpolator = springFast
            }
            animators.add(anim)
            animators.add(scaleAnim)
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
                val anim = ObjectAnimator.ofPropertyValuesHolder(icon,
                    PropertyValuesHolder.ofFloat(View.ALPHA, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f),
                ).apply {
                    duration = 300L
                    startDelay = delay
                    interpolator = springFast
                }
                animators.add(anim)
                globalIndex++
            }
        }

        set.playTogether(animators)
        set.start()
    }

    fun animateSpringBack(target: View) {
        val set = AnimatorSet()
        val scaleAnim = ObjectAnimator.ofPropertyValuesHolder(target,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.07f, 1.0f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.07f, 1.0f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, -12f, 0f)
        )
        scaleAnim.duration = 400L
        scaleAnim.interpolator = springFast
        set.play(scaleAnim)
        set.start()
    }

    fun isAnimating(): Boolean = animating
}
