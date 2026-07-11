package app.lawnchair.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import app.lawnchair.LawnchairLauncher
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.preferences2.subscribeBlocking
import app.lawnchair.theme.ThemeProvider
import app.lawnchair.theme.toAndroidColor
import app.lawnchair.views.EqualizerView
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.views.BaseDragLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AudioDeviceBanner(private val launcher: LawnchairLauncher) {

    companion object {
        var positionOverride: Float? = null
    }

    private val context: Context = launcher
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var overlayView: ViewGroup? = null
    private var closeButtonView: View? = null
    private var isShowing = false
    private var isDismissing = false
    private var callbackRegistered = false
    private var isHidden = false
    private val prefs2 = PreferenceManager2.getInstance(context)
    private val themeProvider = ThemeProvider.INSTANCE.get(context)
    private var bannerTopMargin = 0
    private val density = context.resources.displayMetrics.density
    private var visualizer: Visualizer? = null
    private var positionScope: CoroutineScope? = null

    /** The banner collapses itself to the reveal tab after this long with no interaction. */
    private val autoCloseDelayMs = 5000L
    private val autoCloseRunnable = Runnable {
        if (isShowing && !isDismissing && !isHidden) hideWithAnimation()
    }

    private fun scheduleAutoClose() {
        handler.removeCallbacks(autoCloseRunnable)
        handler.postDelayed(autoCloseRunnable, autoCloseDelayMs)
    }

    private fun cancelAutoClose() {
        handler.removeCallbacks(autoCloseRunnable)
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            for (device in addedDevices) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    showBanner(device)
                    break
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            for (device in removedDevices) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    dismiss()
                    break
                }
            }
        }
    }

    fun register() {
        if (callbackRegistered) return
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        callbackRegistered = true
        checkConnectedState()
    }

    fun unregister() {
        if (!callbackRegistered) return
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        callbackRegistered = false
        dismiss()
    }

    fun checkConnectedState() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                showBanner(device)
                break
            }
        }
    }

    fun showBanner(device: AudioDeviceInfo) {
        if (isShowing && !isDismissing) {
            populateView(device)
            return
        }
        if (overlayView == null) {
            createOverlayView()
        }
        populateView(device)
        showOverlay()
    }

    private fun createOverlayView() {
        overlayView = LayoutInflater.from(context)
            .inflate(R.layout.audio_device_banner, null) as ViewGroup
    }

    private fun populateView(device: AudioDeviceInfo) {
        val view = overlayView ?: return
        val bg = view.findViewById<View>(R.id.banner_root)
        val deviceNameView = view.findViewById<TextView>(R.id.device_name)
        val batteryView = view.findViewById<TextView>(R.id.battery_level)
        val musicIcon = view.findViewById<ImageView>(R.id.music_icon)
        val musicContainer = view.findViewById<View>(R.id.music_icon_container)
        val equalizerView = view.findViewById<EqualizerView>(R.id.equalizer)

        val colorScheme = themeProvider.colorScheme
        val opacity = prefs2.bannerOpacity.firstCached()

        deviceNameView.text = getBluetoothDeviceName(device)
            ?: device.productName?.toString()
            ?: context.getString(R.string.unknown_device)
        batteryView.visibility = View.VISIBLE
        val batteryLevel = readBluetoothBattery(device)
        if (batteryLevel >= 0) {
            batteryView.text = "$batteryLevel%"
            batteryView.setTextColor(colorScheme.neutral1[50]?.toAndroidColor()
                ?: android.graphics.Color.WHITE)
        } else {
            batteryView.visibility = View.GONE
        }

        val bgShape = bg.background?.mutate() as? GradientDrawable
        bgShape?.setColor(colorScheme.neutral1[600]?.toAndroidColor()
            ?: android.graphics.Color.DKGRAY)
        bgShape?.alpha = (opacity * 255).toInt()
        bgShape?.cornerRadius = 20f * density

        val musicBg = musicContainer.background?.mutate() as? GradientDrawable
        musicBg?.setColor(colorScheme.accent1[500]?.toAndroidColor()
            ?: android.graphics.Color.DKGRAY)
        musicBg?.alpha = 77
        musicBg?.cornerRadius = 14f * density

        deviceNameView.setTextColor(colorScheme.neutral1[50]?.toAndroidColor()
            ?: android.graphics.Color.WHITE)

        musicIcon.setImageResource(R.drawable.ic_music_note)
        val musicAppPackage = prefs2.musicAppPackage.firstCached()
        if (musicAppPackage.isNotEmpty()) {
            loadMusicAppIcon(musicIcon, musicAppPackage)
        } else {
            loadActiveMediaAppIcon(musicIcon)
        }

        musicContainer.setOnClickListener {
            scheduleAutoClose()
            animateAndLaunchMusicApp(musicContainer)
        }

        equalizerView?.let { eq ->
            val accentColor = colorScheme.accent1[400]?.toAndroidColor()
            val eqColor = if (accentColor != null) {
                android.graphics.Color.argb(
                    (opacity * 180).toInt(),
                    android.graphics.Color.red(accentColor),
                    android.graphics.Color.green(accentColor),
                    android.graphics.Color.blue(accentColor),
                )
            } else {
                0xCCFFFFFF.toInt()
            }
            eq.setBarColor(eqColor)
        }

        startVisualizer()
        setupPositionObserver()
    }

    private fun setupPositionObserver() {
        if (positionScope != null) return
        positionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        prefs2.bannerPosition.subscribeBlocking(prefs2, positionScope!!) { pos ->
            if (isShowing && !isDismissing) {
                val screenH = context.resources.displayMetrics.heightPixels
                val bannerH = Utilities.dpToPx(160f, context)
                val override = positionOverride
                val actualPos = override ?: pos
                val topMargin = ((screenH - bannerH) * actualPos).toInt()
                bannerTopMargin = topMargin
                updateBannerPosition()
                updateCloseButtonPosition()
            }
        }
    }

    private fun showCloseButton() {
        val dragLayer = launcher.dragLayer
        if (closeButtonView != null) return
        closeButtonView = LayoutInflater.from(context)
            .inflate(R.layout.audio_device_close_button, dragLayer, false)
        closeButtonView?.setOnClickListener { hideWithAnimation() }
        closeButtonView?.apply {
            background?.mutate()?.let { bg ->
                if (bg is GradientDrawable) {
                    val colorScheme = themeProvider.colorScheme
                    val accentColor = colorScheme.accent1[500]?.toAndroidColor()
                    bg.setColor(accentColor ?: android.graphics.Color.DKGRAY)
                    bg.cornerRadius = 14f * density
                }
            }
        }
        val closeBtnSize = Utilities.dpToPx(28f, context)
        val bannerW = Utilities.dpToPx(72f, context)
        val lp = BaseDragLayer.LayoutParams(closeBtnSize, closeBtnSize).apply {
            topMargin = bannerTopMargin - Utilities.dpToPx(14f, context)
            leftMargin = (bannerW - closeBtnSize) / 2
        }
        closeButtonView?.tag = "audio_close"
        dragLayer.addView(closeButtonView, lp)
    }

    private fun updateCloseButtonPosition() {
        val cb = closeButtonView ?: return
        val lp = cb.layoutParams as? BaseDragLayer.LayoutParams ?: return
        lp.topMargin = bannerTopMargin - Utilities.dpToPx(14f, context)
        cb.layoutParams = lp
    }

    private fun updateBannerPosition() {
        val view = overlayView ?: return
        val lp = view.layoutParams as? BaseDragLayer.LayoutParams ?: return
        lp.topMargin = bannerTopMargin
        view.layoutParams = lp
        view.requestLayout()
    }

    private fun hideWithAnimation() {
        val view = overlayView ?: return
        val cb = closeButtonView
        cancelAutoClose()
        isHidden = true
        releaseVisualizer()

        view.findViewById<EqualizerView>(R.id.equalizer)?.onBannerVisibilityChanged(false)

        val slideBanner = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, -view.width.toFloat()).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
        }
        val fadeBanner = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f).apply {
            duration = 250
        }
        val slideClose = ObjectAnimator.ofFloat(cb, View.TRANSLATION_X, 0f, -(cb?.width ?: 0).toFloat()).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
        }
        val fadeClose = ObjectAnimator.ofFloat(cb, View.ALPHA, 1f, 0f).apply {
            duration = 250
        }
        AnimatorSet().apply {
            playTogether(slideBanner, fadeBanner, slideClose, fadeClose)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                    cb?.visibility = View.GONE
                    showRevealButton()
                }
            })
            start()
        }
    }

    private fun showRevealButton() {
        val dragLayer = launcher.dragLayer
        val revealButton = LayoutInflater.from(context)
            .inflate(R.layout.audio_device_reveal_button, dragLayer, false)
        revealButton.setOnClickListener {
            dragLayer.removeView(revealButton)
            revealBanner()
        }
        val rlp = BaseDragLayer.LayoutParams(
            Utilities.dpToPx(32f, context),
            Utilities.dpToPx(48f, context),
        ).apply {
            topMargin = bannerTopMargin
            leftMargin = 0
        }
        revealButton.tag = "audio_reveal"
        dragLayer.addView(revealButton, rlp)
        revealButton.translationX = -Utilities.dpToPx(32f, context).toFloat()
        ObjectAnimator.ofFloat(revealButton, View.TRANSLATION_X, -Utilities.dpToPx(32f, context).toFloat(), 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun revealBanner() {
        val view = overlayView ?: return
        val cb = closeButtonView ?: return
        view.visibility = View.VISIBLE
        cb.visibility = View.VISIBLE
        isHidden = false

        view.findViewById<EqualizerView>(R.id.equalizer)?.onBannerVisibilityChanged(true)
        startVisualizer()

        view.translationX = -view.width.toFloat()
        cb.translationX = -cb.width.toFloat()
        view.alpha = 0f
        cb.alpha = 0f

        val slideIn = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, -view.width.toFloat(), 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
        }
        val fadeIn = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).apply {
            duration = 250
        }
        val slideCb = ObjectAnimator.ofFloat(cb, View.TRANSLATION_X, -cb.width.toFloat(), 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
        }
        val fadeCb = ObjectAnimator.ofFloat(cb, View.ALPHA, 0f, 1f).apply {
            duration = 250
        }
        AnimatorSet().apply {
            playTogether(slideIn, fadeIn, slideCb, fadeCb)
            start()
        }
        scheduleAutoClose()
    }

    private fun loadMusicAppIcon(imageView: ImageView, packageName: String) {
        try {
            imageView.setImageDrawable(context.packageManager.getApplicationIcon(packageName))
        } catch (_: Exception) {
            imageView.setImageResource(R.drawable.ic_music_note)
        }
    }

    private fun loadActiveMediaAppIcon(imageView: ImageView) {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            val sessions = msm.getActiveSessions(null)
            if (sessions.isNotEmpty()) {
                val pkg = sessions.first().packageName ?: return
                imageView.setImageDrawable(context.packageManager.getApplicationIcon(pkg))
            }
        } catch (_: Exception) {}
    }

    private fun animateAndLaunchMusicApp(icon: View) {
        val scaleUp = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.25f).apply {
            duration = 150
            interpolator = DecelerateInterpolator()
        }
        val scaleUpY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.25f).apply {
            duration = 150
            interpolator = DecelerateInterpolator()
        }
        val scaleDown = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1.25f, 1f).apply {
            duration = 100
            interpolator = DecelerateInterpolator()
        }
        val scaleDownY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1.25f, 1f).apply {
            duration = 100
            interpolator = DecelerateInterpolator()
        }
        AnimatorSet().apply {
            play(scaleUp).with(scaleUpY)
            play(scaleDown).with(scaleDownY).after(scaleUp)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    launchMusicApp()
                }
            })
            start()
        }
    }

    private fun launchMusicApp() {
        val pkg = prefs2.musicAppPackage.firstCached().ifEmpty { null }
            ?: getActiveMediaPackage()
            ?: return
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (_: Exception) {}
    }

    private fun getActiveMediaPackage(): String? {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            val sessions = msm.getActiveSessions(null)
            sessions.firstOrNull()?.packageName
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun readBluetoothBattery(device: AudioDeviceInfo): Int {
        try {
            val address = device.address
            if (address.isNullOrEmpty()) return -1
            val btDevice = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) ?: return -1
            return btDevice.batteryLevel
        } catch (_: Exception) {
            return -1
        }
    }

    private fun getBluetoothDeviceName(device: AudioDeviceInfo): String? {
        return try {
            val address = device.address
            if (address.isNullOrEmpty()) return null
            val btDevice = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) ?: return null
            btDevice.name?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun showOverlay() {
        val view = overlayView ?: return
        val dragLayer = launcher.dragLayer

        if (view.parent == null) {
            val bannerW = Utilities.dpToPx(72f, context)
            val screenH = context.resources.displayMetrics.heightPixels
            val bannerH = Utilities.dpToPx(160f, context)
            val defaultPos = prefs2.bannerPosition.firstCached()
            val topMargin = ((screenH - bannerH) * defaultPos).toInt()
            bannerTopMargin = topMargin

            val lp = BaseDragLayer.LayoutParams(
                bannerW,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                this.topMargin = topMargin
                leftMargin = 0
            }
            dragLayer.addView(view, lp)
        }
        isShowing = true
        isDismissing = false
        showCloseButton()
        view.findViewById<EqualizerView>(R.id.equalizer)?.onBannerVisibilityChanged(true)
        startBounceAnimation()
        scheduleAutoClose()
    }

    private fun startBounceAnimation() {
        val view = overlayView ?: return
        val offsetPx = -Utilities.dpToPx(160f, context).toFloat()
        val overshootPx = Utilities.dpToPx(16f, context).toFloat()
        val bouncePx = Utilities.dpToPx(4f, context).toFloat()

        val bounceAnim = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, offsetPx, overshootPx, 0f, -bouncePx, 0f).apply {
            duration = 600
            interpolator = BounceInterpolator()
        }
        val fadeAnim = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).apply {
            duration = 300
            startDelay = 50
        }
        val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 0.8f, 1.05f, 1f).apply {
            duration = 500
            interpolator = DecelerateInterpolator(1.5f)
        }
        val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.8f, 1.05f, 1f).apply {
            duration = 500
            interpolator = DecelerateInterpolator(1.5f)
        }
        val cb = closeButtonView
        val cbAnim = ObjectAnimator.ofFloat(cb, View.ALPHA, 0f, 1f).apply {
            duration = 300
            startDelay = 50
        }
        AnimatorSet().apply {
            playTogether(bounceAnim, fadeAnim, scaleX, scaleY, cbAnim)
            start()
        }
    }

    private fun startVisualizer() {
        try {
            val eqView = overlayView?.findViewById<EqualizerView>(R.id.equalizer) ?: return
            visualizer?.release()
            val captureSizeRange = Visualizer.getCaptureSizeRange()
            val maxCapture = captureSizeRange[1]
            val sessionId = 0
            visualizer = Visualizer(sessionId)
            visualizer?.setCaptureSize(maxCapture)
            visualizer?.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED)
            visualizer?.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS)
            visualizer?.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        if (waveform != null) eqView.updateBars(waveform)
                    }
                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                },
                Visualizer.getMaxCaptureRate() / 3,
                true,
                false,
            )
            visualizer?.enabled = true
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
        } catch (_: Exception) {}
        overlayView?.findViewById<EqualizerView>(R.id.equalizer)?.resetToSilent()
    }

    fun dismiss() {
        if (!isShowing || isDismissing) return
        cancelAutoClose()
        isDismissing = true
        releaseVisualizer()
        overlayView?.findViewById<EqualizerView>(R.id.equalizer)?.onBannerVisibilityChanged(false)

        val view = overlayView ?: run { isDismissing = false; return }
        val cb = closeButtonView
        val offsetPx = -Utilities.dpToPx(160f, context).toFloat()
        val bounceOut = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, offsetPx).apply {
            duration = 300
            interpolator = BounceInterpolator()
        }
        val fadeOut = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f).apply {
            duration = 250
        }
        val slideClose = ObjectAnimator.ofFloat(cb, View.TRANSLATION_X, 0f, -(cb?.width ?: 0).toFloat()).apply {
            duration = 300
            interpolator = BounceInterpolator()
        }
        val fadeClose = ObjectAnimator.ofFloat(cb, View.ALPHA, 1f, 0f).apply {
            duration = 250
        }
        AnimatorSet().apply {
            playTogether(bounceOut, fadeOut, slideClose, fadeClose)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    removeOverlay()
                }
            })
            start()
        }
    }

    private fun removeOverlay() {
        val dragLayer = launcher.dragLayer
        val view = overlayView
        if (view != null && view.parent is ViewGroup) {
            (view.parent as ViewGroup).removeView(view)
        }
        val cb = closeButtonView
        if (cb != null && cb.parent is ViewGroup) {
            (cb.parent as ViewGroup).removeView(cb)
        }
        cancelAutoClose()
        closeButtonView = null
        val revealTag = dragLayer.findViewWithTag<View>("audio_reveal")
        if (revealTag != null && revealTag.parent is ViewGroup) {
            (revealTag.parent as ViewGroup).removeView(revealTag)
        }
        positionScope?.cancel()
        positionScope = null
        overlayView = null
        isShowing = false
        isDismissing = false
        isHidden = false
    }
}
