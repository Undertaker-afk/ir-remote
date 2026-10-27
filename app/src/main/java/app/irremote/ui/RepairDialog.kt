package app.irremote.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.irremote.AppViewModel
import app.irremote.R
import app.irremote.data.*
import app.irremote.ir.IrSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun describe(encoded: String): String = when (val s = IrSignal.decode(encoded)) {
    is IrSignal.Parsed -> "${s.protocol}  ${s.address.toString(16).uppercase()} / ${s.command.toString(16).uppercase()}"
    is IrSignal.Raw -> "raw (${s.timings.size} timings)"
    null -> "?"
}

/** Shows replacement codes for a key that does not work; each can be tested on the device before it is applied. */
@Composable
fun RepairDialog(vm: AppViewModel, saved: SavedRemote, button: IrButton, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var suggestions by remember { mutableStateOf<List<KeyRepair.Suggestion>?>(null) }
    var applied by remember { mutableStateOf<KeyRepair.Suggestion?>(null) }
    LaunchedEffect(button) {
        val all = vm.everything().map { it.second }
        suggestions = withContext(Dispatchers.Default) { KeyRepair.suggest(saved.remote, button.name, all) }
    }
    val reportTitle = stringResource(R.string.repair_report)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.repair_title, button.name.replace('_', ' '))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.repair_current, describe(button.encoded)), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (val list = suggestions) {
                    null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.online_searching)) }
                    else -> if (list.isEmpty()) Text(stringResource(R.string.repair_none))
                    else {
                        Text(stringResource(R.string.repair_hint), fontSize = 13.sp)
                        LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(list) { s ->
                                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        Text(describe(s.encoded), fontSize = 15.sp)
                                        Text(
                                            stringResource(R.string.repair_votes, s.votes) + (s.swapWith?.let { " · " + stringResource(R.string.repair_swap, it.replace('_', ' ')) } ?: ""),
                                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = { vm.press(IrButton(button.name, s.encoded)) }) { Text(stringResource(R.string.repair_test)) }
                                            Button(onClick = {
                                                vm.replaceButton(saved.id, button.name, s.encoded, s.swapWith)
                                                applied = s
                                            }) { Text(stringResource(R.string.repair_use)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                applied?.let { Text(stringResource(R.string.repair_applied), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            TextButton(onClick = {
                val text = KeyRepair.report(saved.remote, button, applied)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, "IR database: ${saved.remote.brand} ${saved.remote.model} - ${button.name}"), reportTitle))
            }) { Text(stringResource(R.string.repair_report)) }
        },
    )
}
