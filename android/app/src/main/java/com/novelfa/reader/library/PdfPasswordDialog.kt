package com.novelfa.reader.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.novelfa.reader.R
import com.novelfa.reader.data.PdfPasswordProvider
import kotlinx.coroutines.CompletableDeferred

/**
 * One outstanding PDF password question, shared by the library and
 * collections screens (both import through [LibraryViewModel]).
 *
 * The importer runs on Dispatchers.IO and suspends in [ask] while the
 * dialog is up; Compose snapshot state is safe to write from any thread,
 * and the deferred is completed exactly once by the first answer
 * (further completes are no-ops). Batch imports ask sequentially, so
 * two questions can never be outstanding at once.
 */
class PdfPasswordPrompt {
    data class Request(
        val fileName: String,
        val retry: Boolean,
        val deferred: CompletableDeferred<String?>
    )

    var request by mutableStateOf<Request?>(null)
        private set

    /** Shows the dialog and suspends until the user answers or cancels. */
    suspend fun ask(fileName: String, retry: Boolean): String? {
        val deferred = CompletableDeferred<String?>()
        request = Request(fileName, retry, deferred)
        return deferred.await()
    }

    fun answer(value: String?) {
        request?.deferred?.complete(value)
        request = null
    }

    /** A [PdfPasswordProvider] bound to this prompt, for the ViewModel. */
    val provider: PdfPasswordProvider
        get() = { fileName, retry -> ask(fileName, retry) }
}

@Composable
fun rememberPdfPasswordPrompt(): PdfPasswordPrompt = remember { PdfPasswordPrompt() }

/**
 * The password dialog. Blank confirm is disabled: a file that reached
 * this dialog already failed the empty password, so submitting blank
 * would only burn one of the importer's three attempts.
 */
@Composable
fun PdfPasswordDialog(prompt: PdfPasswordPrompt) {
    val request = prompt.request ?: return
    // Keyed on the request object: every attempt gets a fresh empty field.
    key(request) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { prompt.answer(null) },
            title = { Text(stringResource(R.string.pdf_password_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.pdf_password_message, request.fileName))
                    if (request.retry) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.pdf_password_wrong))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotEmpty(),
                    onClick = { prompt.answer(password) }
                ) { Text(stringResource(R.string.pdf_password_unlock)) }
            },
            dismissButton = {
                TextButton(onClick = { prompt.answer(null) }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
