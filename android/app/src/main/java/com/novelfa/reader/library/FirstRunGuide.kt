package com.novelfa.reader.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.novelfa.reader.R
import com.novelfa.reader.theme.AppIcon
import com.novelfa.reader.theme.AppIconKind

private data class GuidePage(val icon: AppIconKind, val titleRes: Int, val bodyRes: Int)

private val GuidePages = listOf(
    GuidePage(AppIconKind.Reading, R.string.guide_welcome_title, R.string.guide_welcome_body),
    GuidePage(AppIconKind.Plus, R.string.guide_add_title, R.string.guide_add_body),
    GuidePage(AppIconKind.Pages, R.string.guide_modes_title, R.string.guide_modes_body),
    GuidePage(AppIconKind.Sparkle, R.string.guide_ranks_title, R.string.guide_ranks_body),
    GuidePage(AppIconKind.File, R.string.guide_backup_title, R.string.guide_backup_body)
)

/**
 * First-run guide: five short pages (welcome, add a book, reading
 * modes, XP ranks, backup + themes). Shown once on first launch,
 * before the name prompt; re-openable from the About dialog.
 *
 * [onFinish] runs for both Done (last page) and Skip (first page):
 * the caller decides what "finished" means (first run marks it shown
 * and opens the name prompt; a re-open just closes). [onDismiss]
 * (back/outside) closes without marking it finished.
 */
@Composable
fun FirstRunGuide(onFinish: () -> Unit, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    val last = page == GuidePages.size - 1
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(20.dp)) {
                val p = GuidePages[page]
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(
                        p.icon,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 28.dp
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(p.titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(p.bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GuidePages.indices.forEach { i ->
                        Box(
                            modifier = Modifier
                                .size(if (i == page) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i == page) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                            .copy(alpha = 0.4f)
                                    }
                                )
                        )
                        if (i < GuidePages.size - 1) Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (page > 0) {
                        TextButton(onClick = { page-- }) {
                            Text(stringResource(R.string.guide_prev))
                        }
                    } else {
                        TextButton(onClick = onFinish) {
                            Text(stringResource(R.string.guide_skip))
                        }
                    }
                    if (last) {
                        Button(onClick = onFinish) {
                            Text(stringResource(R.string.guide_start))
                        }
                    } else {
                        Button(onClick = { page++ }) {
                            Text(stringResource(R.string.guide_next))
                        }
                    }
                }
            }
        }
    }
}
