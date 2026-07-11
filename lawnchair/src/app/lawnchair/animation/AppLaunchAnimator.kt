package app.lawnchair.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.palette.graphics.Palette
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.Utilities

private val easeOut = PathInterpolator(0.33f, 0f, 0.2f, 1f)
private val linearInterpolator = LinearInterpolator()

class AppLaunchAnimator(private val launcher: Launcher) {

    private fun openDuration(): Long = 500L

    private fun closeDuration(): Long = 450L

    private var openAnimator: AnimatorSet? = null
    private var closeAnimator: AnimatorSet? = null
    private var closeCardView: View? = null
    private var openCardView: View? = null
    private var returnTarget: View? = null
    private var originalView: View? = null

    private fun cancelOpen() {
        openAnimator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        openAnimator = null
        removeCard("open")
        openOriginalView?.let { v ->
            v.alpha = 1f
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationY = 0f
            v.visibility = View.VISIBLE
        }
        openOriginalView = null
        originalView?.let { v ->
            v.alpha = 1f
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationY = 0f
            v.visibility = View.VISIBLE
        }
        originalView = null
        appLaunched = false
    }

    private fun cancelClose() {
        closeAnimator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        closeAnimator = null
        returnTarget?.let { v ->
            v.alpha = 1f
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationY = 0f
            v.visibility = View.VISIBLE
        }
        returnTarget = null
        removeCard("close")
        closeCardView = null
    }

    private fun extractColors(drawable: Drawable): Pair<Int, Int> {
        return try {
            val w = drawable.intrinsicWidth.coerceAtLeast(48)
            val h = drawable.intrinsicHeight.coerceAtLeast(48)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            val palette = Palette.from(bitmap).generate()
            val vibrant = palette.getVibrantColor(Color.WHITE)
            val muted = palette.getMutedColor(vibrant)
            Pair(vibrant, muted)
        } catch (e: Exception) {
            Pair(Color.WHITE, Color.LTGRAY)
        }
    }

    private var openOriginalView: View? = null
    private var openIconCX = 0f
    private var openIconCY = 0f
    private var appLaunched = false

    private fun animateCloseBack(card: View, targetView: View, iconCX: Float, iconCY: Float, wasCardSize: Int) {
        val bg = card.background as? GradientDrawable
        val endCorner = (wasCardSize / 2f).coerceAtLeast(0f)
        val cornerAnim = ValueAnimator.ofFloat(
            bg?.cornerRadius ?: 0f, endCorner
        ).apply {
            duration = closeDuration()
            interpolator = easeOut
            addUpdateListener {
                bg?.cornerRadius = it.animatedValue as Float
                card.invalidate()
            }
        }
        val moveAnim = ObjectAnimator.ofPropertyValuesHolder(card,
            PropertyValuesHolder.ofFloat(View.SCALE_X, card.scaleX, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, card.scaleY, 1f),
            PropertyValuesHolder.ofFloat(View.X, card.x, iconCX - wasCardSize / 2f),
            PropertyValuesHolder.ofFloat(View.Y, card.y, iconCY - wasCardSize / 2f),
        ).apply {
            duration = closeDuration()
            interpolator = easeOut
        }
        val set = AnimatorSet()
        set.playTogether(moveAnim, cornerAnim)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                card.alpha = 0f
                targetView.alpha = 1f
                val parent = card.parent as? ViewGroup
                parent?.removeView(card)
            }
        })
        set.start()
    }

    fun animateOpen(v: View, onLaunch: Runnable) {
        // Don't cancel close animation — let it finish naturally
        // If an open animation is running, animate the old card back gracefully
        if (openAnimator != null) {
            val oldCard = openCardView
            val oldView = openOriginalView
            openAnimator?.removeAllListeners()
            openAnimator?.cancel()
            openAnimator = null
            openCardView = null
            if (oldCard != null && oldView != null) {
                animateCloseBack(oldCard, oldView, openIconCX, openIconCY, (oldCard.layoutParams as ViewGroup.LayoutParams).width)
            }
        }

        appLaunched = false
        originalView = v
        openOriginalView = v

        val iconBounds = Rect()
        if (v is BubbleTextView) {
            v.getIconBounds(iconBounds)
        }
        val outRect = RectF()
        Utilities.getBoundsForViewInDragLayer(
            launcher.getDragLayer(), v, iconBounds,
            false, null, outRect
        )

        val iconW = (outRect.width().toInt()).coerceAtLeast(4)
        val iconH = (outRect.height().toInt()).coerceAtLeast(4)
        val iconCX = outRect.centerX()
        val iconCY = outRect.centerY()
        openIconCX = iconCX
        openIconCY = iconCY
        val iconDrawable = if (v is BubbleTextView) v.icon?.constantState?.newDrawable()?.mutate() else null

        if (iconDrawable == null) {
            onLaunch.run()
            return
        }

        val dragLayer = launcher.getDragLayer()
        val screenW = dragLayer.width
        val screenH = dragLayer.height
        val cardSize = maxOf(iconW, iconH)
        val startCorner = cardSize / 2f
        val fullScaleY = screenH.toFloat() / cardSize
        val fullScaleX = screenW.toFloat() / cardSize

        val (primaryColor, secondaryColor) = extractColors(iconDrawable)

        v.alpha = 0f

        val cardBg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(primaryColor, secondaryColor)
        ).apply { cornerRadius = startCorner }

        val card = object : FrameLayout(launcher) {
            override fun onTouchEvent(event: MotionEvent): Boolean = false
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false
        }.apply {
            layoutParams = ViewGroup.LayoutParams(cardSize, cardSize)
            x = iconCX - cardSize / 2f
            y = iconCY - cardSize / 2f
            pivotX = cardSize / 2f
            pivotY = cardSize / 2f
            scaleX = 1f
            scaleY = 1f
            background = cardBg
            clipToOutline = true
            isClickable = false
            isFocusable = false
            visibility = View.VISIBLE
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        val iconView = ImageView(launcher).apply {
            setImageDrawable(iconDrawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(iconW, iconH).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        card.addView(iconView)
        dragLayer.addView(card)
        openCardView = card

        val centerX = screenW / 2f
        val centerY = screenH / 2f

        val cornerAnim = ValueAnimator.ofFloat(startCorner, 0f).apply {
            duration = openDuration()
            interpolator = easeOut
            addUpdateListener {
                cardBg.cornerRadius = it.animatedValue as Float
                card.invalidate()
            }
        }

        val openAnim = ObjectAnimator.ofPropertyValuesHolder(card,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, fullScaleX),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, fullScaleY),
            PropertyValuesHolder.ofFloat(View.X, iconCX - cardSize / 2f, centerX - cardSize / 2f),
            PropertyValuesHolder.ofFloat(View.Y, iconCY - cardSize / 2f, centerY - cardSize / 2f),
        ).apply {
            duration = openDuration()
            interpolator = easeOut
        }

        val set = AnimatorSet()
        set.playTogether(openAnim, cornerAnim)

        val isSmooth = try {
            val prefs = app.lawnchair.preferences2.PreferenceManager2.getInstance(launcher)
            prefs.animationType.firstCached() == AnimationType.SMOOTH
        } catch (e: Exception) {
            false
        }

        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                removeCard("open")
                if (isSmooth || !appLaunched) {
                    appLaunched = true
                    onLaunch.run()
                }
                v.alpha = 1f
                openAnimator = null
            }
        })
        if (!isSmooth) {
            openAnim.addUpdateListener {
                if (it.animatedFraction >= 0.35f && !appLaunched) {
                    appLaunched = true
                    onLaunch.run()
                }
            }
        }
        set.start()
        openAnimator = set
    }

    fun animateReturn(v: View, onIconsSpring: Runnable, onDone: Runnable) {
        cancelClose()
        cancelOpen()
        originalView = v
        returnTarget = v

        val iconBounds = Rect()
        if (v is BubbleTextView) {
            v.getIconBounds(iconBounds)
        }
        val outRect = RectF()
        Utilities.getBoundsForViewInDragLayer(
            launcher.getDragLayer(), v, iconBounds,
            true, null, outRect
        )

        val iconW = (outRect.width().toInt()).coerceAtLeast(4)
        val iconH = (outRect.height().toInt()).coerceAtLeast(4)
        val iconCX = outRect.centerX()
        val iconCY = outRect.centerY()
        val iconDrawable = if (v is BubbleTextView) v.icon?.constantState?.newDrawable()?.mutate() else null

        if (iconDrawable == null) {
            v.alpha = 1f
            onDone.run()
            return
        }

        val dragLayer = launcher.getDragLayer()
        val screenW = dragLayer.width
        val screenH = dragLayer.height
        val cardSize = maxOf(iconW, iconH)
        val endCorner = cardSize / 2f
        val fullScaleY = screenH.toFloat() / cardSize
        val fullScaleX = screenW.toFloat() / cardSize
        val centerX = screenW / 2f
        val centerY = screenH / 2f

        val (primaryColor, secondaryColor) = extractColors(iconDrawable)

        v.alpha = 0f

        val cardBg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(primaryColor, secondaryColor)
        ).apply { cornerRadius = 0f }

        val card = object : FrameLayout(launcher) {
            override fun onTouchEvent(event: MotionEvent): Boolean = false
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false
        }.apply {
            layoutParams = ViewGroup.LayoutParams(cardSize, cardSize)
            x = centerX - cardSize / 2f
            y = centerY - cardSize / 2f
            pivotX = cardSize / 2f
            pivotY = cardSize / 2f
            scaleX = fullScaleX
            scaleY = fullScaleY
            background = cardBg
            clipToOutline = true
            isClickable = false
            isFocusable = false
            visibility = View.VISIBLE
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        val iconView = ImageView(launcher).apply {
            setImageDrawable(iconDrawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(iconW, iconH).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        card.addView(iconView)
        dragLayer.addView(card)
        closeCardView = card

        onIconsSpring.run()

        val closeDur = closeDuration()

        val cornerAnim = ValueAnimator.ofFloat(0f, endCorner).apply {
            duration = closeDur
            interpolator = easeOut
            addUpdateListener {
                cardBg.cornerRadius = it.animatedValue as Float
                card.invalidate()
            }
        }

        val shrinkAnim = ObjectAnimator.ofPropertyValuesHolder(card,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -300f, 0f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, fullScaleX, 0.4f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, fullScaleY, 0.4f),
            PropertyValuesHolder.ofFloat(View.X, centerX - cardSize / 2f, iconCX - cardSize / 2f),
            PropertyValuesHolder.ofFloat(View.Y, centerY - cardSize / 2f, iconCY - cardSize / 2f),
        ).apply {
            duration = closeDur
            interpolator = easeOut
        }

        val iconSpring = ObjectAnimator.ofPropertyValuesHolder(v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.3f, 1.08f, 1.0f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.3f, 1.08f, 1.0f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -6f, 0f),
        ).apply {
            duration = 300L
            startDelay = 50L
            interpolator = linearInterpolator
        }

        val set = AnimatorSet()
        set.playTogether(shrinkAnim, cornerAnim, iconSpring)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                v.alpha = 1f
                v.scaleX = 1f
                v.scaleY = 1f
                v.translationY = 0f
                returnTarget = null
                removeCard("close")
                closeAnimator = null
                onDone.run()
            }
        })
        set.start()
        closeAnimator = set
    }

    private fun removeCard(kind: String) {
        val v = if (kind == "open") openCardView else closeCardView
        if (kind == "open") openCardView = null else closeCardView = null
        if (v != null) {
            v.alpha = 1f
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationY = 0f
            v.visibility = View.GONE
            val parent = v.parent as? ViewGroup
            parent?.removeView(v)
        }
    }

}
