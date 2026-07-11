package app.lawnchair.ui.preferences.destinations

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.util.AudioDeviceBanner
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun ActivePlayerPreferences(
    modifier: Modifier = Modifier,
) {
    val prefs2 = preferenceManager2()
    val context = LocalContext.current
    var showAppPicker by remember { mutableStateOf(false) }
    val currentPackage = prefs2.musicAppPackage.getAdapter()

    PreferenceLayout(
        label = "Active Player",
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        PreferenceGroup(heading = "Music App") {
            Item {
                ClickablePreference(
                    label = stringResource(R.string.pick_app_for_gesture),
                    onClick = { showAppPicker = !showAppPicker },
                )
            }
            if (currentPackage.state.value.isNotEmpty()) {
                Item {
                    ClickablePreference(
                        label = "Clear selection",
                        onClick = {
                            currentPackage.onChange("")
                        },
                    )
                }
            }
        }

        if (showAppPicker) {
            AppPickerContent(
                onSelect = { pkg ->
                    currentPackage.onChange(pkg)
                    showAppPicker = false
                },
            )
        }

        PreferenceGroup(heading = "Banner") {
            Item {
                SliderPreference(
                    label = "Transparency",
                    adapter = prefs2.bannerOpacity.getAdapter(),
                    valueRange = 0.1f..1.0f,
                    step = 0.1f,
                    showAsPercentage = true,
                )
            }
            Item {
                PositionSliderItem(prefs2 = prefs2)
            }
        }
    }
}

@Composable
private fun AppPickerContent(
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { context.packageManager.getLaunchIntentForPackage(it.packageName) != null }
                    .sortedBy { context.packageManager.getApplicationLabel(it).toString() }
                    .map { app ->
                        AppInfo(
                            packageName = app.packageName,
                            label = context.packageManager.getApplicationLabel(app).toString(),
                            icon = try {
                                val d = context.packageManager.getApplicationIcon(app.packageName)
                                drawableToBitmap(d)
                            } catch (_: Exception) {
                                null
                            },
                        )
                    }
            } catch (_: Exception) {
                emptyList()
            }
        }
        apps = list
        isLoading = false
    }

    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    } else {
        Column {
            apps.forEach { app ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onSelect(app.packageName) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    app.icon?.let {
                        Icon(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                    } ?: Spacer(Modifier.size(36.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

private data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
)

@Composable
private fun PositionSliderItem(prefs2: app.lawnchair.preferences2.PreferenceManager2) {
    val positionAdapter = prefs2.bannerPosition.getAdapter()
    var sliderValue by remember { mutableFloatStateOf(positionAdapter.state.value) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Position",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(4.dp))
        Slider(
            value = sliderValue,
            onValueChange = { newValue ->
                sliderValue = newValue
                AudioDeviceBanner.positionOverride = newValue
            },
            onValueChangeFinished = {
                AudioDeviceBanner.positionOverride = null
                positionAdapter.onChange(sliderValue)
            },
            valueRange = 0.05f..0.85f,
            steps = 15,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "${(sliderValue * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val w = drawable.intrinsicWidth.coerceAtLeast(1)
    val h = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, w, h)
    drawable.draw(canvas)
    return bitmap
}
