package app.irremote.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.irremote.AppViewModel
import app.irremote.R
import app.irremote.Candidate
import app.irremote.Choice
import app.irremote.Screen
import app.irremote.ir.ac.*
import app.irremote.data.*
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var dbDialog by remember { mutableStateOf(false) }
    var webDialog by remember { mutableStateOf(false) }
    var offRoom by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }?.substringBeforeLast('.')?.replace('_', ' ') ?: "Remote"
            vm.importFile(uri, name) { id ->
                if (id == null) Toast.makeText(context, R.string.import_failed, Toast.LENGTH_LONG).show()
                else vm.push(Screen.Remote(id))
            }
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopBar(stringResource(R.string.my_remotes), null) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.import_file)) }, onClick = { menu = false; picker.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.paste_code)) }, onClick = { menu = false; pasting = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.auto_find)) }, onClick = { menu = false; vm.push(Screen.AutoScan) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.db_title)) }, onClick = { menu = false; dbDialog = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.online_settings_title)) }, onClick = { menu = false; webDialog = true })
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.push(Screen.Categories) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_device))
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            if (!vm.transmitter.available) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(stringResource(R.string.no_emitter), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
                Spacer(Modifier.height(12.dp))
            }
            if (vm.saved.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_remotes), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val rooms = vm.saved.map { it.room }.filter { it.isNotEmpty() }.distinct().sorted()
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (rooms.isEmpty()) {
                        items(vm.saved, key = { it.id }) { s -> RemoteCard(vm, s) }
                    } else for (room in rooms + listOf("")) {
                        val list = vm.saved.filter { it.room == room }
                        if (list.isEmpty()) continue
                        item(key = "room_$room") {
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    room.ifEmpty { stringResource(R.string.other_devices) }, Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                )
                                if (room.isNotEmpty()) TextButton(onClick = { offRoom = room }) {
                                    Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.all_off))
                                }
                            }
                        }
                        items(list, key = { it.id }) { s -> RemoteCard(vm, s) }
                    }
                }
            }
            Text(
                stringResource(R.string.credits), Modifier.fillMaxWidth().padding(8.dp),
                textAlign = TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (dbDialog) DbUpdateDialog(vm) { dbDialog = false }
    if (webDialog) WebSettingsDialog(vm) { webDialog = false }
    if (pasting) PasteDialog(onDismiss = { pasting = false }) { text, name ->
        pasting = false
        vm.importText(text, name) { id ->
            if (id == null) Toast.makeText(context, R.string.import_failed, Toast.LENGTH_LONG).show() else vm.push(Screen.Remote(id))
        }
    }
    offRoom?.let { room ->
        AlertDialog(
            onDismissRequest = { offRoom = null },
            title = { Text(stringResource(R.string.all_off_title, room)) },
            text = { Text(stringResource(R.string.all_off_note)) },
            confirmButton = { TextButton(onClick = { vm.powerOffRoom(room); offRoom = null }) { Text(stringResource(R.string.all_off)) } },
            dismissButton = { TextButton(onClick = { offRoom = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RemoteCard(vm: AppViewModel, s: SavedRemote) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable { vm.push(Screen.Remote(s.id)) },
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(s.category.icon, null, Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(s.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(s.category.label(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onImport: (String, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paste_code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.paste_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.remote_name)) })
                OutlinedTextField(text, { text = it }, minLines = 5, maxLines = 8, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text, name.ifBlank { "Imported" }) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.import_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------- Category grid

@Composable
fun CategoriesScreen(vm: AppViewModel) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopBar(stringResource(R.string.add_device), onBack = { vm.pop() }) {
                IconButton(onClick = { vm.push(Screen.Discover) }) { Icon(Icons.Filled.Cast, stringResource(R.string.find_nearby)) }
                IconButton(onClick = { vm.push(Screen.Search) }) { Icon(Icons.Filled.Search, stringResource(R.string.search_models)) }
            }
        },
    ) { pad ->
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.padding(pad).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(Category.entries.filter { it != Category.CUSTOM }) { c ->
                Surface(
                    shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().height(116.dp).clickable { vm.push(Screen.Brands(c)) },
                ) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(c.icon, null, Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(c.label(), textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Brand list

private fun initial(name: String): Char = name.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrandsScreen(vm: AppViewModel, category: Category) {
    var brands by remember { mutableStateOf<List<Brand>?>(null) }
    LaunchedEffect(category) { brands = vm.brands(category) }
    var query by remember { mutableStateOf("") }
    val all = brands.orEmpty()
    val list = if (query.isBlank()) all else all.filter { it.name.contains(query.trim(), ignoreCase = true) }
    val popular = remember(all) { all.sortedByDescending { it.remotes.size }.take(4) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val header = if (query.isBlank() && popular.isNotEmpty()) 3 else 0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopBar(stringResource(R.string.choose_brand, category.label()), onBack = { vm.pop() }) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                query, { query = it }, singleLine = true, shape = CircleShape,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.search_brands)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (brands == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else Row(Modifier.weight(1f)) {
                LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(start = 16.dp, top = 8.dp)) {
                    if (header > 0) {
                        item { Text(stringResource(R.string.popular_brands), Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                popular.forEach { b ->
                                    Surface(
                                        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.clickable { vm.push(Screen.Wizard(category, b.name)) },
                                    ) { Text(b.name, Modifier.padding(horizontal = 24.dp, vertical = 16.dp), fontWeight = FontWeight.Medium) }
                                }
                            }
                        }
                        item { Text(stringResource(R.string.all_brands), Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
                    }
                    items(list, key = { it.name }) { b ->
                        Text(
                            b.name, fontSize = 20.sp,
                            modifier = Modifier.fillMaxWidth().clickable { vm.push(Screen.Wizard(category, b.name)) }.padding(vertical = 16.dp, horizontal = 4.dp),
                        )
                    }
                }
                if (query.isBlank()) Column(Modifier.fillMaxHeight().padding(end = 4.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                    ('A'..'Z').forEach { ch ->
                        Text(
                            ch.toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable {
                                val i = list.indexOfFirst { initial(it.name) == ch }
                                if (i >= 0) scope.launch { state.scrollToItem(i + header) }
                            }.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Auto-match wizard

@Composable
fun WizardScreen(vm: AppViewModel, category: Category, brandName: String, hint: String = "") {
    var brand by remember { mutableStateOf<Brand?>(null) }
    LaunchedEffect(brandName) { brand = vm.brands(category).firstOrNull { it.name == brandName } }
    var scores by remember { mutableStateOf<Map<IrRemote, Match>?>(null) }
    // with a model hint (from device discovery or a typed model) the candidates are ordered by how well they fit it
    LaunchedEffect(hint) { if (hint.isNotBlank()) scores = vm.engine().scoreMap(hint) }
    var isOn by remember { mutableStateOf<Boolean?>(null) }
    var index by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val remoteName = "$brandName ${category.label()}"

    val slots = if (isOn == true) listOf(Slot.MUTE, Slot.VOL_UP, Slot.POWER) else listOf(Slot.POWER, Slot.MUTE)
    if (category == Category.AC) LaunchedEffect(Unit) { isOn = false } // A/C wizard always tests "power on"
    val candidates = remember(brand, isOn, scores) {
        val keys = brand?.let { b -> if (isOn == null) emptyList() else vm.candidates(b, slots, scores = scores) }.orEmpty().map { Choice.Keys(it) }
        val smart = if (category == Category.AC && isOn != null) AcProtocols.orderedFor(brandName).map { Choice.Smart(it) } else emptyList()
        smart + keys
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopBar("$brandName ${category.label()}", onBack = { vm.pop() }) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (isOn == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(category.icon, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.is_on, category.label()), fontSize = 22.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(32.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onClick = { isOn = true }, Modifier.width(140.dp).height(52.dp)) { Text(stringResource(R.string.on)) }
                        Button(onClick = { isOn = false }, Modifier.width(140.dp).height(52.dp)) { Text(stringResource(R.string.off)) }
                    }
                }
            } else if (candidates.isEmpty()) {
                Text(
                    stringResource(R.string.no_match), Modifier.align(Alignment.Center).padding(32.dp),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (index >= candidates.size) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.exhausted), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { index = 0 }) { Text(stringResource(R.string.start_over)) }
                }
            } else {
                val choice = candidates[index]
                val testSlot = if (choice is Choice.Keys) slots.first { choice.c.remote.find(it) != null } else Slot.POWER
                val testLabel = if (choice is Choice.Keys) choice.c.testButton.name else choice.let { (it as Choice.Smart).p.name }
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(48.dp))
                    Text(stringResource(R.string.wizard_hint), fontSize = 20.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        stringResource(R.string.wizard_progress, index + 1, candidates.size),
                        color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center,
                    )
                    (choice as? Choice.Keys)?.c?.match?.takeIf { it.tier <= Tier.SAME_FAMILY }?.let { m ->
                        Text("${stringResource(R.string.wizard_ranked)}: ${m.tier.label()} ${m.reason}".trim(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(56.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        RoundKey(enabled = index > 0, size = 48, onPress = { index-- }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                                    .irPress { haptic.performHapticFeedback(HapticFeedbackType.LongPress); testChoice(vm, choice) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(slotIcon(testSlot), null, Modifier.size(48.dp), tint = Color.White) }
                            Spacer(Modifier.height(12.dp))
                            Text(testLabel)
                        }
                        RoundKey(size = 48, onPress = { index++ }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            val s = when (choice) {
                                is Choice.Keys -> vm.addRemote(category, remoteName, choice.c.remote)
                                is Choice.Smart -> vm.addAcRemote(remoteName, choice.p.id)
                            }
                            vm.popToHome(); vm.push(Screen.Remote(s.id))
                        },
                        Modifier.fillMaxWidth().height(52.dp).padding(bottom = 0.dp),
                    ) { Text(stringResource(R.string.works)) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

internal fun testChoice(vm: AppViewModel, choice: Choice) {
    when (choice) {
        is Choice.Keys -> vm.press(choice.c.testButton)
        is Choice.Smart -> vm.transmitter.send(choice.p.encode(AcState(power = true, mode = AcMode.COOL, temp = 24, fan = AcFan.AUTO)))
    }
}

fun slotIcon(s: Slot) = when (s) {
    Slot.POWER -> Icons.Filled.PowerSettingsNew
    Slot.MUTE -> Icons.AutoMirrored.Filled.VolumeOff
    Slot.VOL_UP -> Icons.AutoMirrored.Filled.VolumeUp
    else -> Icons.Filled.TouchApp
}

// ---------------------------------------------------------------- Remote

private enum class RemoteView { STANDARD, ALL, CUSTOM }

private val AllKeysCategories = setOf(Category.AC, Category.FAN, Category.LIGHT, Category.CLEANER)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(vm: AppViewModel, id: String) {
    val saved = vm.saved.firstOrNull { it.id == id }
    if (saved == null) { LaunchedEffect(Unit) { vm.pop() }; return }
    if (saved.acProtocol != null) { AcScreen(vm, saved); return }
    val remote = saved.remote
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val mapped = Slot.entries.count { remote.find(it) != null }
    var view by remember(id) {
        mutableStateOf(
            when {
                saved.custom.isNotEmpty() -> RemoteView.CUSTOM
                saved.category in AllKeysCategories || mapped < 4 -> RemoteView.ALL
                else -> RemoteView.STANDARD
            }
        )
    }
    var editing by remember { mutableStateOf(false) }
    var repairMode by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf<IrButton?>(null) }
    var roomDialog by remember { mutableStateOf(false) }
    if (roomDialog) RoomDialog(saved.room, vm.saved.map { it.room }.filter { it.isNotEmpty() }.distinct().sorted(), { roomDialog = false }) { vm.setRoom(id, it); roomDialog = false }
    if (editing) {
        LayoutEditor(saved, onCancel = { editing = false }, onSave = { vm.setCustom(id, it); view = if (it.isEmpty()) RemoteView.STANDARD else RemoteView.CUSTOM; editing = false })
        return
    }
    var sheet by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val notSent = stringResource(R.string.not_sent)

    fun fire(b: IrButton?) {
        if (b == null || repairing != null) return
        if (repairMode) { repairMode = false; repairing = b; return }   // "key doesn't work": the next tap picks the key to fix
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (!vm.press(b) && vm.transmitter.available) Toast.makeText(context, notSent, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopBar(if (repairMode) stringResource(R.string.repair_tap) else saved.name, onBack = { if (repairMode) repairMode = false else vm.pop() }) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.repair_menu)) }, onClick = { repairMode = true; menu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.standard_layout)) }, onClick = { view = RemoteView.STANDARD; menu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.all_keys)) }, onClick = { view = RemoteView.ALL; menu = false })
                        if (saved.custom.isNotEmpty()) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.my_layout)) }, onClick = { view = RemoteView.CUSTOM; menu = false })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.edit_layout)) }, onClick = { editing = true; menu = false })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.volume_keys)) },
                            trailingIcon = { Checkbox(vm.volumeKeys, null) },
                            onClick = { vm.toggleVolumeKeys(!vm.volumeKeys) },
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.room_menu)) }, onClick = { roomDialog = true; menu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { renaming = true; menu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; vm.delete(id) })
                    }
                }
            }
        },
    ) { pad ->
        if (view != RemoteView.STANDARD) {
            val keys = if (view == RemoteView.CUSTOM) {
                saved.custom.mapNotNull { k -> remote.buttons.firstOrNull { it.name == k.button }?.let { k.label to it } }
            } else remote.buttons.map { it.name to it }
            LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.padding(pad).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(keys) { (label, b) -> TextKey(label) { fire(b) } }
            }
        } else Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            @Composable fun Key(slot: Slot, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color? = null) {
                val b = remote.find(slot)
                RoundKey(enabled = b != null, repeat = slot.repeats, onPress = { fire(b) }) {
                    Icon(icon, slot.name, tint = tint ?: LocalContentColor.current)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Key(Slot.POWER, Icons.Filled.PowerSettingsNew, if (remote.find(Slot.POWER) != null) Color(0xFF3DC47E) else null)
                Key(Slot.INPUT, Icons.Filled.Login)
                Key(Slot.MENU, Icons.Filled.Menu)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Key(Slot.HOME, Icons.Filled.Home)
                RoundKey(enabled = Slot.digits.any { remote.find(it) != null }, onPress = { sheet = "keys" }) { Icon(Icons.Filled.Dialpad, stringResource(R.string.keypad)) }
                Key(Slot.BACK, Icons.AutoMirrored.Filled.Undo)
            }
            Spacer(Modifier.height(28.dp))
            DPad(remote, ::fire)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill(remote, Slot.CH_UP, Slot.CH_DOWN, Icons.Filled.KeyboardArrowUp, Icons.Filled.KeyboardArrowDown, ::fire)
                    RoundKey(onPress = { sheet = "more" }) { Icon(Icons.Filled.MoreHoriz, stringResource(R.string.more_keys)) }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill(remote, Slot.VOL_UP, Slot.VOL_DOWN, Icons.Filled.Add, Icons.Filled.Remove, ::fire)
                    Key(Slot.MUTE, Icons.AutoMirrored.Filled.VolumeOff)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    repairing?.let { b -> RepairDialog(vm, saved, b) { repairing = null } }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }) {
        if (sheet == "keys") {
            Column(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Slot.digits.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        row.forEach { s -> DigitKey(s.name.removePrefix("D"), remote.find(s) != null) { fire(remote.find(s)) } }
                    }
                }
            }
        } else {
            val extra = remember(remote) { remote.extraButtons() }
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3,
            ) { extra.forEach { b -> TextKey(b.name, Modifier.weight(1f)) { fire(b) } } }
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(saved.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            confirmButton = { TextButton(onClick = { vm.rename(id, text); renaming = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        )
    }
}

@Composable
private fun TextKey(label: String, modifier: Modifier = Modifier, onPress: () -> Unit) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).irPress(onPress = onPress),
        contentAlignment = Alignment.Center,
    ) { Text(label.replace('_', ' '), maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp), fontSize = 14.sp) }
}

@Composable
private fun DigitKey(label: String, enabled: Boolean, onPress: () -> Unit) {
    RoundKey(size = 72, enabled = enabled, onPress = onPress) { Text(label, fontSize = 28.sp) }
}

@Composable
private fun Pill(remote: IrRemote, up: Slot, down: Slot, upIcon: androidx.compose.ui.graphics.vector.ImageVector, downIcon: androidx.compose.ui.graphics.vector.ImageVector, fire: (IrButton?) -> Unit) {
    val u = remote.find(up)
    val d = remote.find(down)
    Column(
        Modifier.width(64.dp).height(150.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (u != null || d != null) 1f else 0.4f)),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth().irPress(u != null, true) { fire(u) }, contentAlignment = Alignment.Center) { Icon(upIcon, up.name) }
        Box(Modifier.weight(1f).fillMaxWidth().irPress(d != null, true) { fire(d) }, contentAlignment = Alignment.Center) { Icon(downIcon, down.name) }
    }
}

@Composable
private fun DPad(remote: IrRemote, fire: (IrButton?) -> Unit) {
    val ring = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(Modifier.size(240.dp).clip(CircleShape).background(ring), contentAlignment = Alignment.Center) {
        @Composable fun Arrow(slot: Slot, icon: androidx.compose.ui.graphics.vector.ImageVector, align: Alignment) {
            val b = remote.find(slot)
            Box(Modifier.align(align).size(72.dp).irPress(b != null, true) { fire(b) }, contentAlignment = Alignment.Center) {
                Icon(icon, slot.name, tint = LocalContentColor.current.copy(alpha = if (b != null) 1f else 0.3f))
            }
        }
        Arrow(Slot.UP, Icons.Filled.KeyboardArrowUp, Alignment.TopCenter)
        Arrow(Slot.DOWN, Icons.Filled.KeyboardArrowDown, Alignment.BottomCenter)
        Arrow(Slot.LEFT, Icons.AutoMirrored.Filled.KeyboardArrowLeft, Alignment.CenterStart)
        Arrow(Slot.RIGHT, Icons.AutoMirrored.Filled.KeyboardArrowRight, Alignment.CenterEnd)
        val ok = remote.find(Slot.OK)
        RoundKey(size = 88, enabled = ok != null, onPress = { fire(ok) }) {
            Text(stringResource(R.string.ok), fontSize = 20.sp)
        }
    }
}


// ---------------------------------------------------------------- Custom layout editor

/** Pick buttons, rename them and order them; the result becomes the remote's "my layout" grid. */
@Composable
private fun LayoutEditor(saved: SavedRemote, onCancel: () -> Unit, onSave: (List<CustomKey>) -> Unit) {
    val chosen = remember { mutableStateListOf<CustomKey>().apply { addAll(saved.custom.filter { k -> saved.remote.buttons.any { it.name == k.button } }) } }
    var renaming by remember { mutableStateOf<Int?>(null) }
    BackHandler(onBack = onCancel)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopBar(stringResource(R.string.edit_layout), onBack = onCancel) {
                TextButton(onClick = { onSave(chosen.toList()) }) { Text(stringResource(R.string.done)) }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            item { Text(stringResource(R.string.my_layout), Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
            itemsIndexed(chosen) { i, k ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { renaming = i }.padding(vertical = 8.dp)) {
                        Text(k.label, fontSize = 17.sp)
                        if (k.label != k.button) Text(k.button, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { if (i > 0) { val t = chosen[i]; chosen[i] = chosen[i - 1]; chosen[i - 1] = t } }, enabled = i > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up))
                    }
                    IconButton(onClick = { if (i < chosen.size - 1) { val t = chosen[i]; chosen[i] = chosen[i + 1]; chosen[i + 1] = t } }, enabled = i < chosen.size - 1) {
                        Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down))
                    }
                    IconButton(onClick = { chosen.removeAt(i) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove_key)) }
                }
            }
            item { Text(stringResource(R.string.all_keys), Modifier.padding(top = 20.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
            items(saved.remote.buttons.filter { b -> chosen.none { it.button == b.name } }) { b ->
                Row(Modifier.fillMaxWidth().clickable { chosen.add(CustomKey(b.name, b.name.replace('_', ' '))) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(b.name.replace('_', ' '), Modifier.weight(1f), fontSize = 16.sp)
                    Icon(Icons.Filled.Add, stringResource(R.string.add_key))
                }
            }
        }
    }
    renaming?.let { i ->
        var text by remember(i) { mutableStateOf(chosen[i].label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) chosen[i] = chosen[i].copy(label = text.trim()); renaming = null }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        )
    }
}
