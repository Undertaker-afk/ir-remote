package app.irremote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.irremote.AppViewModel
import app.irremote.Choice
import app.irremote.Screen
import app.irremote.R
import app.irremote.data.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class ScanPhase { IDLE, RUNNING, CONFIRM }

private val scannable = listOf(Category.TV, Category.SETTOP, Category.AC, Category.AVR, Category.PROJECTOR, Category.DVD, Category.STREAM, Category.FAN, Category.LIGHT)

/** Sends the power code of every known remote one after another; the user stops it when the device reacts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoScanScreen(vm: AppViewModel) {
    var category by remember { mutableStateOf(Category.TV) }
    var choices by remember { mutableStateOf<List<Choice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var phase by remember { mutableStateOf(ScanPhase.IDLE) }
    var index by remember { mutableIntStateOf(0) }
    var seconds by remember { mutableFloatStateOf(1.5f) }

    LaunchedEffect(category) { loading = true; phase = ScanPhase.IDLE; index = 0; choices = vm.scanChoices(category); loading = false }

    LaunchedEffect(phase, index, choices) {
        if (phase != ScanPhase.RUNNING) return@LaunchedEffect
        if (index >= choices.size) { phase = ScanPhase.IDLE; return@LaunchedEffect }
        withContext(Dispatchers.Default) { testChoice(vm, choices[index]) }
        delay((seconds * 1000).toLong())
        index++
    }

    val categoryLabel = category.label()

    fun label(c: Choice) = when (c) {
        is Choice.Keys -> "${c.c.remote.brand} ${c.c.remote.model.removePrefix(c.c.remote.brand).trim()}".trim()
        is Choice.Smart -> "${c.p.name} (A/C)"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopBar(stringResource(R.string.auto_find), onBack = { vm.pop() }) },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                scannable.forEach { c ->
                    FilterChip(selected = category == c, onClick = { if (phase != ScanPhase.RUNNING) category = c }, label = { Text(c.label()) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.auto_find_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            if (loading) { CircularProgressIndicator(); return@Column }
            if (choices.isEmpty()) { Text(stringResource(R.string.no_results)); return@Column }

            val current = choices.getOrNull(index.coerceAtMost(choices.size - 1))
            LinearProgressIndicator(progress = { (index + 1).toFloat() / choices.size }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.auto_find_progress, index + 1, choices.size), color = MaterialTheme.colorScheme.primary)
            if (current != null && phase != ScanPhase.IDLE) Text(label(current), fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(24.dp))

            when (phase) {
                ScanPhase.IDLE -> {
                    Text(stringResource(R.string.auto_find_speed, seconds), fontSize = 13.sp)
                    Slider(seconds, { seconds = it }, valueRange = 0.6f..3f)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { index = 0; phase = ScanPhase.RUNNING }, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.auto_find_start)) }
                }
                ScanPhase.RUNNING -> {
                    Button(
                        onClick = { index = (index - 1).coerceAtLeast(0); phase = ScanPhase.CONFIRM },
                        Modifier.size(180.dp), shape = CircleShape,
                    ) { Text(stringResource(R.string.auto_find_reacted), fontSize = 20.sp, textAlign = TextAlign.Center) }
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = { phase = ScanPhase.IDLE }) { Text(stringResource(R.string.auto_find_stop)) }
                }
                ScanPhase.CONFIRM -> {
                    Text(stringResource(R.string.auto_find_confirm), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { index = (index - 1).coerceAtLeast(0); current?.let { testChoice(vm, choices[index]) } }, enabled = index > 0) { Text(stringResource(R.string.previous)) }
                        OutlinedButton(onClick = { current?.let { testChoice(vm, it) } }) { Text(stringResource(R.string.send_again)) }
                        OutlinedButton(onClick = { index = (index + 1).coerceAtMost(choices.size - 1); testChoice(vm, choices[index]) }, enabled = index < choices.size - 1) { Text(stringResource(R.string.next)) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        val ch = current ?: return@Button
                        val name = when (ch) {
                            is Choice.Keys -> "${ch.c.remote.brand} $categoryLabel"
                            is Choice.Smart -> "${ch.p.name} $categoryLabel"
                        }
                        val s = when (ch) {
                            is Choice.Keys -> vm.addRemote(category, name, ch.c.remote)
                            is Choice.Smart -> vm.addAcRemote(name, ch.p.id)
                        }
                        vm.popToHome(); vm.push(Screen.Remote(s.id))
                    }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.works)) }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { index++; phase = ScanPhase.RUNNING }) { Text(stringResource(R.string.auto_find_continue)) }
                }
            }
        }
    }
}
