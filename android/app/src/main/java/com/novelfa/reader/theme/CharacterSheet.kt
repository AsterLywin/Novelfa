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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

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
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                CharacterRow(
                    letter = "✦",
                    name = "بدون تم شخصیت",
                    tagline = "برگشت به رنگ ساده‌ی برنامه",
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
                            letter = theme.nameFa.firstOrNull()?.toString() ?: "؟",
                            name = theme.nameFa,
                            tagline = theme.taglineFa,
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
                        text = "ذرات متحرک",
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
            Text(text = letter, color = hexToColor(nightBg))
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
