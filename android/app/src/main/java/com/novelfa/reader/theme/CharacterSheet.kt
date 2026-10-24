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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import android.util.Log
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.novelfa.reader.R

/**
 * Unlock conditions for one character theme, in both independent gates:
 * the legacy finished-books rank and the XP title. The caller (settings)
 * fills the localized rank/title names and the current counts.
 */
data class ThemeUnlockInfo(
    val legacyDone: Int,
    val legacyNeed: Int,
    val legacyRankName: String,
    val xpNow: Int,
    val xpNeed: Int,
    val xpTitleName: String
)

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
    onToggleParticles: (Boolean) -> Unit = {},
    /**
     * Loads unlock conditions for a theme on demand. When provided,
     * tapping a locked row loads its conditions and opens the
     * unlock-conditions dialog instead of doing nothing. Loading on
     * tap (not when the sheet opens) avoids a race where a tap before
     * the preload finished would silently do nothing.
     */
    unlockInfoLoader: (suspend (CharacterTheme) -> ThemeUnlockInfo?)? = null
) {
    val isEnglishUi = LocalConfiguration.current.locales[0].language.startsWith("en")
    var pendingTheme by remember { mutableStateOf<CharacterTheme?>(null) }
    var showParticlesNotice by remember { mutableStateOf(false) }
    var infoTheme by remember { mutableStateOf<Pair<CharacterTheme, ThemeUnlockInfo>?>(null) }
    LaunchedEffect(pendingTheme) {
        val theme = pendingTheme ?: return@LaunchedEffect
        val loader = unlockInfoLoader
        if (loader == null) {
            pendingTheme = null
            return@LaunchedEffect
        }
        try {
            loader(theme)?.let { infoTheme = theme to it }
        } catch (e: Exception) {
            Log.w("NovelfaTheme", "Theme unlock info load failed", e)
        }
        pendingTheme = null
    }
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
                            onClick = {
                                if (locked) {
                                    pendingTheme = theme
                                } else {
                                    onPick(theme.id)
                                }
                            }
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
                        onCheckedChange = { checked ->
                            // Particles render only when a character
                            // theme supplies their type (ParticleLayer
                            // returns early on a blank type), so turning
                            // them on with plain colors shows nothing.
                            // The choice is still saved — it takes
                            // effect as soon as a theme is picked — but
                            // the user must be told why nothing appears.
                            if (checked && activeId == null) showParticlesNotice = true
                            onToggleParticles(checked)
                        }
                    )
                }
            }
        }
    }
    if (showParticlesNotice) {
        AlertDialog(
            onDismissRequest = { showParticlesNotice = false },
            title = { Text(stringResource(R.string.particles_no_theme_title)) },
            text = { Text(stringResource(R.string.particles_no_theme_message)) },
            confirmButton = {
                TextButton(onClick = { showParticlesNotice = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
    infoTheme?.let { (theme, info) ->
        ThemeUnlockDialog(
            theme = theme,
            info = info,
            isEnglishUi = isEnglishUi,
            onDismiss = { infoTheme = null }
        )
    }
}

@Composable
private fun ThemeUnlockDialog(
    theme: CharacterTheme,
    info: ThemeUnlockInfo,
    isEnglishUi: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(theme.displayName(isEnglishUi)) },
        text = {
            Column {
                Text(
                    text = theme.displayTagline(isEnglishUi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.theme_unlock_either),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.theme_unlock_books,
                        info.legacyDone, info.legacyNeed, info.legacyRankName
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = {
                        if (info.legacyNeed > 0) {
                            (info.legacyDone.toFloat() / info.legacyNeed.toFloat()).coerceIn(0f, 1f)
                        } else 1f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.theme_unlock_xp,
                        info.xpNow, info.xpNeed, info.xpTitleName
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = {
                        if (info.xpNeed > 0) {
                            (info.xpNow.toFloat() / info.xpNeed.toFloat()).coerceIn(0f, 1f)
                        } else 1f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok_2))
            }
        }
    )
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
            .clickable(onClick = onClick)
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
                    withTransform({ scale(scale, scale, androidx.compose.ui.geometry.Offset.Zero) }) {
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
            } else if (letter == "✦") {
                // The no-theme row's mark: the Windows sparkle, drawn.
                AppIcon(AppIconKind.Sparkle, tint = hexToColor(nightBg), size = 20.dp)
            } else {
                Text(text = letter, color = hexToColor(nightBg))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (locked) {
                    // Windows locked-card badge: IcoLock in a dark circle.
                    Box(
                        modifier = Modifier
                            .size(19.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF262335)),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(AppIconKind.Lock, tint = Color.White, size = 11.dp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = name,
                    // Trim the font's built-in padding/leading so the
                    // lock badge and check center on the visible glyphs
                    // (Persian fonts carry very tall ascent metrics,
                    // which pushed the row's icons a line too high).
                    style = MaterialTheme.typography.titleMedium.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both
                        )
                    )
                )
                if (active) {
                    Spacer(modifier = Modifier.width(6.dp))
                    AppIcon(
                        AppIconKind.Check,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 15.dp
                    )
                }
            }
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
