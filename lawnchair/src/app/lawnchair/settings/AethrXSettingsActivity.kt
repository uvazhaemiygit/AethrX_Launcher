package app.lawnchair.settings

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.android.launcher3.R

class AethrXSettingsActivity : Activity() {

    companion object {
        const val KEY_OPEN_DURATION = "aethrx_open_duration"
        const val KEY_CLOSE_DURATION = "aethrx_close_duration"
        const val DEFAULT_OPEN_DURATION = 550L
        const val DEFAULT_CLOSE_DURATION = 450L
        const val MIN_DURATION = 200
        const val MAX_DURATION = 1000
    }

    private lateinit var prefs: SharedPreferences
    private var pendingOpen = DEFAULT_OPEN_DURATION.toInt()
    private var pendingClose = DEFAULT_CLOSE_DURATION.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aethrx_settings)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        pendingOpen = prefs.getLong(KEY_OPEN_DURATION, DEFAULT_OPEN_DURATION).toInt().coerceIn(MIN_DURATION, MAX_DURATION)
        pendingClose = prefs.getLong(KEY_CLOSE_DURATION, DEFAULT_CLOSE_DURATION).toInt().coerceIn(MIN_DURATION, MAX_DURATION)

        setupSeekBar(R.id.open_speed_seekbar, R.id.open_speed_value) { pendingOpen }
        setupSeekBar(R.id.close_speed_seekbar, R.id.close_speed_value) { pendingClose }

        findViewById<Button>(R.id.apply_button).setOnClickListener {
            prefs.edit()
                .putLong(KEY_OPEN_DURATION, pendingOpen.toLong())
                .putLong(KEY_CLOSE_DURATION, pendingClose.toLong())
                .apply()
            finish()
        }
    }

    private fun setupSeekBar(seekBarId: Int, valueId: Int, value: () -> Int) {
        val seekBar = findViewById<SeekBar>(seekBarId)
        val valueView = findViewById<TextView>(valueId)
        seekBar.max = (MAX_DURATION - MIN_DURATION) / 50
        seekBar.progress = (value() - MIN_DURATION) / 50
        valueView.text = "${value()}ms"

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = MIN_DURATION + progress * 50
                if (seekBarId == R.id.open_speed_seekbar) pendingOpen = v
                else pendingClose = v
                valueView.text = "${v}ms"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }
}
