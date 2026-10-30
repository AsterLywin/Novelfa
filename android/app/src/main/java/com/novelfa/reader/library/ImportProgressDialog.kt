package com.novelfa.reader.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.novelfa.reader.R
import com.novelfa.reader.theme.AppIcon
import com.novelfa.reader.theme.AppIconKind

/**
 * The ONE import progress dialog, shared by the library and the
 * collections screens (they used to have two different ones: the
 * library's had a stage line and a Cancel button, the collections'
 * was a bare spinner with no way out at all, so the same import
 * looked and behaved differently depending on which tab started it).
 *
 * Styled like Novelfa's own Card dialogs (first-run guide, character
 * sheet) instead of a bare Material AlertDialog: accent icon, title,
 * the file actually being imported, "file N of M" for batches, and
 * the importer's live stage in plain words. The stage is a key, not
 * a pre-built sentence, so the dialog and the importer can never
 * drift apart again: a stage the importer never reports cannot be
 * shown, and a stage it does report is mapped here in one place.
 *
 * Cancel only cancels the import job (see the callers): the dialog
 * itself is not dismissible by back press or outside tap, so a stray
 * tap can never silently orphan a running import.
 */
@Composable
fun ImportProgressDialog(
    /** Original file name of the file currently importing, if known. */
    fileName: String?,
    /** 1-based index of the file currently importing. */
    fileIndex: Int,
    /** Total files in this import batch. */
    fileTotal: Int,
    /** Importer stage key: "copy", "decrypt", "validate", "parse", "save". */
    stage: String?,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = {}) {
        Card {
            Column(modifier = Modifier.padding(20.dp)) {
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
                        AppIconKind.Download,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 28.dp
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.import_in_progress),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (fileName != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (fileTotal > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.import_progress_file, fileIndex, fileTotal),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                val stageText = when (stage) {
                    "copy" -> stringResource(R.string.import_stage_copy)
                    "decrypt" -> stringResource(R.string.import_stage_decrypt)
                    "validate" -> stringResource(R.string.import_stage_validate)
                    "parse" -> stringResource(R.string.import_stage_parse)
                    "save" -> stringResource(R.string.import_stage_save)
                    else -> null
                }
                if (stageText != null) {
                    Text(
                        text = stageText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = stringResource(R.string.import_large_files_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}
