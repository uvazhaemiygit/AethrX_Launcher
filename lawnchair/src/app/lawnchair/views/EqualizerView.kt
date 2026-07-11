package app.lawnchair.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.sin

class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barCount = 6
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val audioLevels = FloatArray(barCount) { 0f }
    private val phases = FloatArray(barCount) { it * 0.9f + 0.3f }
    private var animator: ValueAnimator? = null
    private var progress = 0f
    private var hasAudioData = false
    private var animatorActive = false

    fun setBarColor(color: Int) {
        barPaint.color = color
        invalidate()
    }

    fun updateBars(waveform: ByteArray) {
        if (!animatorActive) startAnimator()
        hasAudioData = true
        val samplesPerBar = waveform.size / barCount
        for (i in 0 until barCount) {
            var sum = 0f
            val start = i * samplesPerBar
            val end = minOf(start + samplesPerBar, waveform.size)
            for (j in start until end) {
                sum += abs(waveform[j.coerceAtMost(waveform.size - 1)].toInt()).toFloat() / 128f
            }
            val avg = sum / (end - start).coerceAtLeast(1)
            audioLevels[i] = audioLevels[i] * 0.3f + avg.coerceIn(0f, 1f) * 0.7f
        }
        postInvalidateOnAnimation()
    }

    fun resetToSilent() {
        hasAudioData = false
        audioLevels.fill(0f)
        if (animatorActive) invalidate()
    }

    fun onBannerVisibilityChanged(visible: Boolean) {
        if (visible) {
            startAnimator()
        } else {
            stopAnimator()
        }
    }

    private fun startAnimator() {
        if (animatorActive) return
        animatorActive = true
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            setDuration(900)
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedFraction
                if (!hasAudioData) invalidate()
            }
            start()
        }
    }

    private fun stopAnimator() {
        animatorActive = false
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val barWidth = w / (barCount * 2f)
        val spacing = barWidth

        if (hasAudioData) {
            for (i in 0 until barCount) {
                val barHeight = audioLevels[i].coerceIn(0.05f, 1f) * h * 0.85f
                val x = spacing + i * (barWidth + spacing)
                val y = h - barHeight
                canvas.drawRoundRect(x, y, x + barWidth, h - 1f, 1.5f, 1.5f, barPaint)
            }
        } else if (animatorActive) {
            for (i in 0 until barCount) {
                val v = sin(progress * Math.PI * 2 + phases[i]).toFloat()
                val barHeight = ((v + 1f) * 0.3f + 0.2f) * h * 0.85f
                val x = spacing + i * (barWidth + spacing)
                val y = h - barHeight
                canvas.drawRoundRect(x, y, x + barWidth, h - 1f, 1.5f, 1.5f, barPaint)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(42, widthMeasureSpec),
            resolveSize(32, heightMeasureSpec),
        )
    }
}
