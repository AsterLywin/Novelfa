package com.novelfa.reader.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.novelfa.reader.R
import kotlinx.coroutines.launch

/** PinDialog modes, mirroring PinWindow.xaml.cs setup/verify/change flow. */
enum class PinMode { SETUP, VERIFY, CHANGE }

/**
 * Brute-force throttle for PIN verify attempts (process-lifetime): after 5
 * consecutive failures the dialog refuses further attempts for 30 seconds.
 */
object PinThrottle {
    private const val MAX_FAILURES = 5
    private const val LOCKOUT_MS = 30_000L
    private var failures = 0
    private var lockedUntilMs = 0L

    fun isLockedOut(): Boolean = System.currentTimeMillis() < lockedUntilMs

    fun remainingSeconds(): Long =
        ((lockedUntilMs - System.currentTimeMillis()) / 1000).coerceAtLeast(1)

    fun recordFailure() {
        if (++failures >= MAX_FAILURES) {
            failures = 0
            lockedUntilMs = System.currentTimeMillis() + LOCKOUT_MS
        }
    }

    fun reset() {
        failures = 0
        lockedUntilMs = 0L
    }
}

/**
 * Process-lifetime session unlock: the private tab shows the lock +
 * PinDialog while `hasPin && !unlocked`, and sets `unlocked = true` on
 * success. The lock button re-locks by setting it back to false.
 */
object PinSession {
    var unlocked: Boolean = false
}

/**
 * PIN dialog ported from PinWindow.xaml.cs: numeric fields, fa labels and
 * error texts (R.string keys from the Task 4 map), confirm/cancel callbacks.
 */
@Composable
fun PinDialog(
    mode: PinMode,
    manager: PinManager,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var oldPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // DataStore-backed manager calls are suspend: run them off the main thread.
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val emptyErr = stringResource(R.string.the_pin_can_t_be_empty)
    val shortErr = stringResource(R.string.the_pin_must_be_at_least_4_characters)
    val mismatchErr = stringResource(R.string.the_pins_don_t_match)
    val wrongErr = stringResource(R.string.wrong_pin)
    // Format template resolved at composition; seconds filled in on click
    // (stringResource with args is @Composable-only).
    val lockoutFmt = stringResource(R.string.pin_locked_out)
    fun lockoutErr(): String = lockoutFmt.format(PinThrottle.remainingSeconds())

    val title = stringResource(
        when (mode) {
            PinMode.SETUP -> R.string.create_a_pin
            PinMode.VERIFY -> R.string.enter_your_pin
            PinMode.CHANGE -> R.string.enter_the_new_pin
        }
    )
    val subtitle = stringResource(
        when (mode) {
            PinMode.SETUP -> R.string.to_lock_your_private_books
            PinMode.VERIFY -> R.string.the_private_section_is_locked
            PinMode.CHANGE -> R.string.the_new_pin_will_replace_the_old_one
        }
    )

    /** Synchronous field validation; returns an error string or null. Raw input (no trim), like C#. */
    fun validateSetup(pin: String, confirm: String): String? {
        if (pin.isEmpty()) return emptyErr
        if (pin.length < 4) return shortErr
        if (pin != confirm) return mismatchErr
        return null
    }

    fun onConfirm() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                when (mode) {
                    PinMode.VERIFY -> {
                        if (first.isEmpty()) {
                            error = emptyErr
                        } else if (PinThrottle.isLockedOut()) {
                            error = lockoutErr()
                        } else if (manager.verify(first)) {
                            PinThrottle.reset()
                            onSuccess()
                        } else {
                            PinThrottle.recordFailure()
                            first = ""
                            error = if (PinThrottle.isLockedOut()) lockoutErr() else wrongErr
                        }
                    }
                    PinMode.SETUP -> {
                        val err = validateSetup(first, second)
                        if (err != null) {
                            error = err
                        } else {
                            try {
                                manager.setPin(first)
                                onSuccess()
                            } catch (e: PinException) {
                                error = when (e.resId) {
                                        R.string.wrong_pin -> wrongErr
                                        else -> shortErr
                                    }
                            }
                        }
                    }
                    PinMode.CHANGE -> {
                        if (PinThrottle.isLockedOut()) {
                            error = lockoutErr()
                        } else if (!manager.verify(oldPin)) {
                            PinThrottle.recordFailure()
                            oldPin = ""
                            error = if (PinThrottle.isLockedOut()) lockoutErr() else wrongErr
                        } else {
                            PinThrottle.reset()
                            val err = validateSetup(first, second)
                            if (err != null) {
                                error = err
                            } else {
                                try {
                                    manager.changePin(oldPin, first)
                                    onSuccess()
                                } catch (e: PinException) {
                                    error = when (e.resId) {
                                        R.string.wrong_pin -> wrongErr
                                        else -> shortErr
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (mode == PinMode.CHANGE) {
                    PinField(
                        value = oldPin,
                        onValueChange = { oldPin = it },
                        label = stringResource(R.string.pin)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                PinField(
                    value = first,
                    onValueChange = { first = it },
                    label = stringResource(if (mode == PinMode.CHANGE) R.string.new_pin else R.string.pin)
                )
                if (mode != PinMode.VERIFY) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PinField(
                        value = second,
                        onValueChange = { second = it },
                        label = stringResource(R.string.repeat_pin)
                    )
                }
                error?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::onConfirm, enabled = !busy) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    )
}
