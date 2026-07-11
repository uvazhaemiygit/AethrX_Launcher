package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lawnchair.animation.AnimationType
import app.lawnchair.preferences2.firstCached
import app.lawnchair.preferences2.preferenceManager2
import com.patrykmichalik.opto.core.setBlocking
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout

@Composable
fun AnimationPreferences(modifier: Modifier = Modifier) {
    val prefs2 = preferenceManager2()
    var selected by remember { mutableStateOf(prefs2.animationType.firstCached()) }

    PreferenceLayout(
        label = "Animations",
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        PreferenceGroup(heading = "Animation Type") {
            Item {
                AnimationOption(
                    title = "Fast",
                    description = "Quick snappy animations",
                    isSelected = selected == AnimationType.FAST,
                    onClick = {
                        selected = AnimationType.FAST
                        prefs2.animationType.setBlocking(AnimationType.FAST)
                    },
                )
            }
            Item {
                AnimationOption(
                    title = "Smooth",
                    description = "Slower fluid animations",
                    isSelected = selected == AnimationType.SMOOTH,
                    onClick = {
                        selected = AnimationType.SMOOTH
                        prefs2.animationType.setBlocking(AnimationType.SMOOTH)
                    },
                )
            }
        }
    }
}

@Composable
private fun AnimationOption(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 16.dp),
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null,
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
