package app.irremote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.irremote.R
import app.irremote.data.Category
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Category.label(): String = stringResource(
    when (this) {
        Category.TV -> R.string.cat_tv
        Category.SETTOP -> R.string.cat_settop
        Category.AC -> R.string.cat_ac
        Category.FAN -> R.string.cat_fan
        Category.STREAM -> R.string.cat_stream
        Category.AVR -> R.string.cat_avr
        Category.DVD -> R.string.cat_dvd
        Category.PROJECTOR -> R.string.cat_projector
        Category.CAMERA -> R.string.cat_camera
        Category.LIGHT -> R.string.cat_light
        Category.CLEANER -> R.string.cat_cleaner
        Category.COMPUTER -> R.string.cat_computer
        Category.CUSTOM -> R.string.cat_custom
    }
)

val Category.icon: ImageVector
    get() = when (this) {
        Category.TV -> Icons.Filled.Tv
        Category.SETTOP -> Icons.Filled.SettingsInputHdmi
        Category.AC -> Icons.Filled.AcUnit
        Category.FAN -> Icons.Filled.Air
        Category.STREAM -> Icons.Filled.Cast
        Category.AVR -> Icons.Filled.Speaker
        Category.DVD -> Icons.Filled.Album
        Category.PROJECTOR -> Icons.Filled.Theaters
        Category.CAMERA -> Icons.Filled.PhotoCamera
        Category.LIGHT -> Icons.Filled.Lightbulb
        Category.CLEANER -> Icons.Filled.CleaningServices
        Category.COMPUTER -> Icons.Filled.SportsEsports
        Category.CUSTOM -> Icons.Filled.FileOpen
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** Tap sends once; holding a repeatable key (volume, channel, arrows) keeps sending like a real remote. */
fun Modifier.irPress(enabled: Boolean = true, repeat: Boolean = false, onPress: () -> Unit): Modifier = composed {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(onPress)
    pointerInput(enabled, repeat) {
        if (enabled) detectTapGestures(onPress = {
            current()
            val job = if (repeat) scope.launch { delay(450); while (true) { current(); delay(160) } } else null
            tryAwaitRelease()
            job?.cancel()
        })
    }
}

@Composable
fun RoundKey(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    repeat: Boolean = false,
    size: Int = 64,
    onPress: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (enabled) 1f else 0.4f))
            .irPress(enabled, repeat, onPress),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
/** Pick an existing room, type a new one, or clear it. */
@Composable
fun RoomDialog(current: String, rooms: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.room_menu)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.room_name)) })
                if (rooms.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rooms.forEach { r -> AssistChip(onClick = { text = r }, label = { Text(r) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(text.trim()) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { onPick("") }) { Text(stringResource(R.string.no_room)) } },
    )
}

/** Shows which database is in use and lets the user download fresh snapshots of Flipper-IRDB and irdb. */
@Composable
fun DbUpdateDialog(vm: app.irremote.AppViewModel, onDismiss: () -> Unit) {
    val info = vm.dbInfo
    AlertDialog(
        onDismissRequest = { if (!vm.dbBusy) onDismiss() },
        title = { Text(stringResource(R.string.db_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val parts = info?.split('|')
                Text(if (parts == null) stringResource(R.string.db_bundled) else stringResource(R.string.db_updated, parts[0], parts.getOrElse(1) { "?" }))
                Text(stringResource(R.string.db_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.dbBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(vm.dbStatus.orEmpty(), fontSize = 13.sp) }
                else vm.dbStatus?.let { s ->
                    if (s.startsWith("OK")) Text(stringResource(R.string.db_done, s.removePrefix("OK: ")), color = MaterialTheme.colorScheme.primary)
                    else Text(stringResource(R.string.db_failed, s.removePrefix("ERROR: ")), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.updateDatabase() }, enabled = !vm.dbBusy) { Text(stringResource(R.string.db_update)) } },
        dismissButton = {
            Row {
                if (info != null) TextButton(onClick = { vm.resetDatabase() }, enabled = !vm.dbBusy) { Text(stringResource(R.string.db_reset)) }
                TextButton(onClick = onDismiss, enabled = !vm.dbBusy) { Text(stringResource(R.string.close)) }
            }
        },
    )
}
