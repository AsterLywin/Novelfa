package com.novelfa.reader.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.novelfa.reader.R

/**
 * Character-theme picker. Each row shows an initial-letter avatar in
 * palette colors (per the binding ruling: no WPF Geometry emblems on
 * mobile), the fa name, tagline, a lock mark when locked, and
 * night/day preview dots.
 *
 * Selection is persisted by the caller via PrefsStore CHAR_THEME
 * ("charTheme"); pass null id to go back to plain app colors.
 */
@Composable
fun CharacterSheet(
    activeId: String?,
    isUnlocked: (CharacterTheme) -> Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    particlesEnabled: Boolean = false,
    onToggleParticles: (Boolean) -> Unit = {}
) {
    val isEnglishUi = LocalConfiguration.current.locales[0].language.startsWith("en")
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                CharacterRow(
                    letter = "✦",
                    name = stringResource(R.string.no_character_theme),
                    tagline = stringResource(R.string.no_character_theme_tagline),
                    nightBg = "#1C1A29",
                    dayBg = "#FFFFFF",
                    accent = "#7C3AED",
                    locked = false,
                    active = activeId == null,
                    onClick = { onPick(null) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(CharacterThemes.All) { theme ->
                        val locked = !isUnlocked(theme)
                        CharacterRow(
                            letter = theme.displayName(isEnglishUi).firstOrNull()?.toString() ?: "?",
                            theme = theme,
                            name = theme.displayName(isEnglishUi),
                            tagline = theme.displayTagline(isEnglishUi),
                            nightBg = theme.dark.card,
                            dayBg = theme.light.hero1,
                            accent = theme.dark.accent,
                            locked = locked,
                            active = activeId == theme.id,
                            onClick = { if (!locked) onPick(theme.id) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // WPF parity: particle layer toggle in the picker footer.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.animated_particles),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = particlesEnabled,
                        onCheckedChange = onToggleParticles
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacterRow(
    letter: String,
    theme: CharacterTheme? = null,
    name: String,
    tagline: String,
    nightBg: String,
    dayBg: String,
    accent: String,
    locked: Boolean,
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !locked, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(hexToColor(accent)),
            contentAlignment = Alignment.Center
        ) {
            val emblem = remember(theme?.id) { theme?.emblemPath }
            if (emblem != null) {
                Canvas(modifier = Modifier.size(28.dp)) {
                    // Scale the 100x100 emblem to fit
                    val scale = size.minDimension / 100f
                    withTransform({ scale(scale, scale) }) {
                        drawPath(
                            path = emblem,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    hexToColor(theme!!.dark.accent),
                                    hexToColor(theme.dark.grad1)
                                )
                            ),
                            alpha = 0.9f
                        )
                    }
                }
            } else {
                Text(text = letter, color = hexToColor(nightBg))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = (if (locked) "🔒 " else "") + name +
                    (if (active) " ✓" else ""),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = tagline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(hexToColor(nightBg))
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(hexToColor(dayBg))
        )
    }
}
