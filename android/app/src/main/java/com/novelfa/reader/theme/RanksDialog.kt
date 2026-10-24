package com.novelfa.reader.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelfa.reader.R

/**
 * Full ranks overview: all 12 titles with their XP thresholds and stars,
 * then the 10 eternity marks. States per row: achieved (threshold shown),
 * current (progress bar + XP to the next step), locked (XP required).
 * Opened from the home rank badge and from the settings Progress card.
 * Pure function of [totalXp] — no store access, so it always matches the
 * level the header and the settings card show.
 */
@Composable
fun RanksDialog(totalXp: Int, isEnglishUi: Boolean, onDismiss: () -> Unit) {
    val level = remember(totalXp) { XpRank.levelFor(totalXp) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ranks_title)) },
        text = {
            Column {
                Text(
                    text = level.title.displayName(isEnglishUi),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                StarRow(fill = level.starFill, starSize = 18.dp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.xp_total_label, totalXp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    items(XpRank.Titles) { title ->
                        RankTitleRow(title = title, level = level, isEnglishUi = isEnglishUi)
                    }
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.ranks_eternity),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    items(XpRank.EternityMarks) { mark ->
                        RankMarkRow(mark = mark, level = level, isEnglishUi = isEnglishUi)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun RankTitleRow(title: XpTitle, level: XpLevel, isEnglishUi: Boolean) {
    val achieved = title.index < level.titleIndex
    val isCurrent = title.index == level.titleIndex
    val locked = !achieved && !isCurrent
    // Drawn fill: achieved titles full, the current title fractional
    // (progress inside it), locked titles empty outlines.
    val fill = when {
        achieved -> 5f
        isCurrent -> level.starFill
        else -> 0f
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.displayName(isEnglishUi),
                style = MaterialTheme.typography.bodyLarge,
                color = if (locked) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Spacer(modifier = Modifier.height(2.dp))
            when {
                achieved -> Text(
                    text = stringResource(R.string.ranks_from_xp, title.minXp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                isCurrent -> {
                    LinearProgressIndicator(
                        progress = { level.progress.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val nextName = if (level.titleIndex < XpRank.Titles.size - 1) {
                        XpRank.Titles[level.titleIndex + 1].displayName(isEnglishUi)
                    } else {
                        XpRank.eternityMarkFor(level.eternityMarks + 1).displayName(isEnglishUi)
                    }
                    Text(
                        text = stringResource(R.string.xp_more_to, level.xpToNext, nextName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> Text(
                    text = stringResource(R.string.ranks_xp_required, title.minXp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        StarRow(fill = fill, starSize = 15.dp)
    }
}

@Composable
private fun RankMarkRow(mark: EternityMark, level: XpLevel, isEnglishUi: Boolean) {
    val pastCap = level.xp >= XpRank.CAP_XP
    val earned = pastCap && mark.index <= level.eternityMarks
    val isCurrent = pastCap && mark.index == level.eternityMarks + 1
    val locked = !earned && !isCurrent
    val markXp = XpRank.CAP_XP + mark.index * XpRank.MARK_STEP_XP
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mark.displayName(isEnglishUi),
                style = MaterialTheme.typography.bodyLarge,
                color = if (locked) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Spacer(modifier = Modifier.height(2.dp))
            when {
                earned -> Text(
                    text = stringResource(R.string.ranks_from_xp, markXp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                isCurrent -> {
                    LinearProgressIndicator(
                        progress = { level.progress.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.xp_more_to,
                            level.xpToNext,
                            mark.displayName(isEnglishUi)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> Text(
                    text = stringResource(R.string.ranks_xp_required, markXp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (locked) "○" else "✦",
            style = MaterialTheme.typography.bodyMedium,
            color = if (locked) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
    }
}
