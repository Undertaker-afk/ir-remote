package app.irremote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import app.irremote.AppViewModel
import app.irremote.R
import app.irremote.web.LookupResult
import app.irremote.web.PartVote
import app.irremote.web.PartVoting

private sealed interface LookupState {
    data object Idle : LookupState
    data object Loading : LookupState
    data class Done(val result: LookupResult) : LookupState
    data class Failed(val message: String) : LookupState
}

/**
 * "Look up online": asks a web search for the remote part numbers of the model in [text] and passes the ranked parts to
 * [onParts], which the screen feeds back into the matching engine. Asks for consent first; nothing is sent before.
 */
@Composable
fun OnlineLookupCard(vm: AppViewModel, text: String, onParts: (List<PartVote>) -> Unit) {
    var state by remember(text) { mutableStateOf<LookupState>(LookupState.Idle) }
    var consent by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val model = remember(text) { PartVoting.guessModel(text) }
    val brand = remember(text) { text.split(Regex("[\\s,;()\"']+")).firstOrNull { it.length >= 3 && it.all(Char::isLetter) && it.firstOrNull()?.isUpperCase() == true }.orEmpty() }

    fun run() {
        state = LookupState.Loading
        scope.launch {
            vm.lookupOnline(brand, model).fold(
                onSuccess = { state = LookupState.Done(it); onParts(it.votes) },
                onFailure = { state = LookupState.Failed(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.online_title), fontWeight = FontWeight.Medium)
            when (val st = state) {
                LookupState.Idle -> {
                    Text(stringResource(R.string.online_hint, model), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (vm.webConsent) run() else consent = true }) { Text(stringResource(R.string.online_button)) }
                        TextButton(onClick = { settings = true }) { Text(stringResource(R.string.online_settings)) }
                    }
                }
                LookupState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.online_searching))
                }
                is LookupState.Done -> {
                    val r = st.result
                    if (r.votes.isEmpty()) Text(stringResource(R.string.online_nothing), fontSize = 14.sp)
                    else {
                        Text(
                            stringResource(when (r.confidence) { PartVoting.Confidence.HIGH -> R.string.online_high; PartVoting.Confidence.MEDIUM -> R.string.online_medium; PartVoting.Confidence.LOW -> R.string.online_low }),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        )
                        r.votes.take(3).forEach { v -> Text("${v.part}  ·  ${stringResource(R.string.online_listings, v.hits)}", fontSize = 15.sp) }
                    }
                    Text(stringResource(R.string.online_source, r.provider + if (r.cached) " (saved)" else ""), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is LookupState.Failed -> {
                    Text(stringResource(R.string.online_failed, st.message), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { run() }) { Text(stringResource(R.string.online_retry)) }
                        TextButton(onClick = { settings = true }) { Text(stringResource(R.string.online_settings)) }
                    }
                }
            }
        }
    }
    if (consent) AlertDialog(
        onDismissRequest = { consent = false },
        title = { Text(stringResource(R.string.online_consent_title)) },
        text = { Text(stringResource(R.string.online_consent_body, "$brand $model".trim(), providerHost(vm.webProvider))) },
        confirmButton = { TextButton(onClick = { vm.saveWebSettings(true, vm.webProvider, vm.webKey, vm.webUrl); consent = false; run() }) { Text(stringResource(R.string.online_allow)) } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (settings) WebSettingsDialog(vm) { settings = false }
}

private fun providerHost(id: String) = when (id) { "tavily" -> "api.tavily.com"; "brave" -> "api.search.brave.com"; "searx" -> "your SearXNG instance"; else -> "mcp.exa.ai" }

/** Which search service to use, and its key/URL. Exa works without a key; the others need one (or an instance URL). */
@Composable
fun WebSettingsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var consent by remember { mutableStateOf(vm.webConsent) }
    var provider by remember { mutableStateOf(vm.webProvider) }
    var key by remember { mutableStateOf(vm.webKey) }
    var url by remember { mutableStateOf(vm.webUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.online_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.online_allow_switch), Modifier.weight(1f)); Switch(consent, { consent = it })
                }
                listOf("exa" to R.string.provider_exa, "tavily" to R.string.provider_tavily, "brave" to R.string.provider_brave, "searx" to R.string.provider_searx).forEach { (id, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(provider == id, { provider = id }); Text(stringResource(label), fontSize = 14.sp) }
                }
                if (provider == "searx") OutlinedTextField(url, { url = it }, singleLine = true, label = { Text(stringResource(R.string.online_url)) })
                else OutlinedTextField(key, { key = it }, singleLine = true, label = { Text(stringResource(if (provider == "exa") R.string.online_key_optional else R.string.online_key)) })
                Text(stringResource(R.string.online_privacy), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveWebSettings(consent, provider, key, url); onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
