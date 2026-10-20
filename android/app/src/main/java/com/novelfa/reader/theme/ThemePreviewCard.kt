package com.novelfa.reader.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.novelfa.reader.reader.ReaderViewModel

private fun parseThemeColor(hex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

/**
 * Reading-theme card with a mini palette preview: the theme's background
 * with two text-color lines and an accent bar, so the user sees what each
 * theme looks like before picking it. The active theme gets a border.
 */
@Composable
fun ThemePreviewCard(
    theme: ReaderTheme,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = parseThemeColor(theme.bg, Color.DarkGray)
    val text = parseThemeColor(theme.text, Color.White)
    val accent = parseThemeColor(theme.thumb, MaterialTheme.colorScheme.primary)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(bg)
                    .padding(10.dp)
            ) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth(0.85f)
                            .height(9.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(text.copy(alpha = 0.9f))
                    )
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.65f)
                            .height(9.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(text.copy(alpha = 0.55f))
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).background(accent))
                }
            }
            Text(
                text = ReaderViewModel.themeDisplayName(
                    theme.key,
                    LocalConfiguration.current.locales[0].language.startsWith("en")
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
