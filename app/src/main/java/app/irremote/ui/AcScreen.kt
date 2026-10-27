package app.irremote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.irremote.AppViewModel
import app.irremote.R
import app.irremote.data.SavedRemote
import app.irremote.ir.ac.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AcScreen(vm: AppViewModel, saved: SavedRemote) {
    val proto = AcProtocols.byId(saved.acProtocol)
    val state = saved.acState ?: AcState()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopBar(saved.name, onBack = { vm.pop() }) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { renaming = true; menu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; vm.delete(saved.id) })
                    }
                }
            }
        },
    ) { pad ->
        if (proto == null) return@Scaffold
        fun update(s: AcState) = vm.setAcState(saved.id, s)
        val accent = if (state.power) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            Text("${state.temp}°C", fontSize = 72.sp, fontWeight = FontWeight.Light)
            Text(proto.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundKey(size = 64, onPress = { update(state.copy(temp = (state.temp - 1).coerceAtLeast(proto.minTemp))) }) { Icon(Icons.Filled.Remove, null) }
                Box(
                    Modifier.size(88.dp).clip(CircleShape).background(accent).clickable { update(state.copy(power = !state.power)) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(40.dp), tint = if (state.power) Color.White else LocalContentColor.current) }
                RoundKey(size = 64, onPress = { update(state.copy(temp = (state.temp + 1).coerceAtMost(proto.maxTemp))) }) { Icon(Icons.Filled.Add, null) }
            }
            Spacer(Modifier.height(32.dp))
            Section(stringResource(R.string.ac_mode))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                proto.modes.forEach { m ->
                    FilterChip(selected = state.mode == m, onClick = { update(state.copy(mode = m, power = true)) }, label = { Text(modeLabel(m)) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Section(stringResource(R.string.ac_fan))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AcFan.entries.forEach { f ->
                    FilterChip(selected = state.fan == f, onClick = { update(state.copy(fan = f, power = true)) }, label = { Text(fanLabel(f)) })
                }
            }
            if (proto.hasSwing) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ac_swing), Modifier.weight(1f))
                    Switch(state.swing, { update(state.copy(swing = it, power = true)) })
                }
            }
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(saved.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            confirmButton = { TextButton(onClick = { vm.rename(saved.id, text); renaming = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        )
    }
}

@Composable
private fun Section(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
}

@Composable
private fun modeLabel(m: AcMode) = stringResource(
    when (m) { AcMode.AUTO -> R.string.mode_auto; AcMode.COOL -> R.string.mode_cool; AcMode.DRY -> R.string.mode_dry; AcMode.FAN -> R.string.mode_fan; AcMode.HEAT -> R.string.mode_heat }
)

@Composable
private fun fanLabel(f: AcFan) = stringResource(
    when (f) { AcFan.AUTO -> R.string.mode_auto; AcFan.LOW -> R.string.fan_low; AcFan.MEDIUM -> R.string.fan_medium; AcFan.HIGH -> R.string.fan_high }
)
