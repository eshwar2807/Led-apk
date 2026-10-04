package com.eshwar.reelplay.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private sealed interface SendState {
    data object Editing : SendState
    data object Sending : SendState
    data object Sent : SendState
    data class Failed(val message: String) : SendState
}

/**
 * "Report a problem": the user describes what happened, sees exactly what will be sent
 * (their words, [details] such as an error, device info and optionally the app's log), and
 * sends it to the developer in one tap. [onSent] runs once it's delivered.
 */
@Composable
fun ReportDialog(
    kind: Reports.Kind,
    details: String = "",
    title: String = "Report a problem",
    onDismiss: () -> Unit,
    onSent: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var includeLog by remember { mutableStateOf(true) }
    var log by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<SendState>(SendState.Editing) }
    LaunchedEffect(Unit) { log = Reports.appLog() }
    val body = Reports.compose(context, message, details, if (includeLog) log else null)

    AlertDialog(
        onDismissRequest = { if (state != SendState.Sending) onDismiss() },
        title = { Text(if (state == SendState.Sent) "Thanks — report sent" else title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    SendState.Sent -> Text("The developer will see it with the details below.")
                    else -> {
                        OutlinedTextField(
                            value = message,
                            onValueChange = { message = it },
                            label = { Text("What happened?") },
                            placeholder = { Text("What you were doing, what you expected…") },
                            minLines = 3,
                            enabled = s != SendState.Sending,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth().clickable { includeLog = !includeLog },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(includeLog, onCheckedChange = { includeLog = it })
                            Text("Include the app's recent log (helps find the cause)")
                        }
                        if (s == SendState.Sending) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (s is SendState.Failed) {
                            Text(s.message, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "What will be sent. Your phone's name, email and network addresses are removed; " +
                                "only what you type above is sent as written.",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            body.take(4_000) + if (body.length > 4_000) "\n…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                SendState.Sent -> TextButton(onClick = onDismiss) { Text("Done") }
                else -> TextButton(
                    enabled = state != SendState.Sending && (message.isNotBlank() || details.isNotBlank()),
                    onClick = {
                        state = SendState.Sending
                        scope.launch {
                            state = try {
                                Reports.send(kind, message.ifBlank { kind.label }, body)
                                onSent()
                                SendState.Sent
                            } catch (e: Exception) {
                                SendState.Failed(e.message ?: "Couldn't send the report")
                            }
                        }
                    },
                ) { Text("Send") }
            }
        },
        dismissButton = {
            if (state != SendState.Sent) {
                Row {
                    if (state is SendState.Failed) {
                        TextButton(onClick = { context.startActivity(Reports.shareIntent(kind, body)) }) {
                            Text("Share instead")
                        }
                    }
                    TextButton(onClick = onDismiss, enabled = state != SendState.Sending) { Text("Cancel") }
                }
            }
        },
    )
}
