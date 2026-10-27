package app.irremote.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import app.irremote.AppViewModel
import app.irremote.R
import app.irremote.Screen
import app.irremote.data.*

/** Lists TVs and casting targets around the phone, like the screen-mirroring picker. */
@Composable
fun DiscoverScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val devices = remember { mutableStateListOf<FoundDevice>() }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    var asked by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) request.launch(permission) else asked = true }

    // restart discovery once the permission answer is in, so Wi-Fi Direct (Miracast) peers can be included
    DisposableEffect(asked, granted) {
        if (!asked) return@DisposableEffect onDispose { }
        val d = DeviceDiscovery(context) { devices.add(it) }
        d.start(includeWifiDirect = granted)
        onDispose { d.close() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopBar(stringResource(R.string.find_nearby), onBack = { vm.pop() }) },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.discover_hint), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(devices.toList()) { d ->
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().clickable { vm.push(Screen.DeviceMatch(d)) },
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Cast, null, Modifier.size(28.dp))
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(d.name, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                val detail = listOfNotNull(d.manufacturer, d.model, d.source).joinToString(" · ")
                                Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What we learned about the chosen device and the remotes that fit it best. */
@Composable
fun DeviceMatchScreen(vm: AppViewModel, device: FoundDevice) {
    var engine by remember { mutableStateOf<MatchEngine?>(null) }
    LaunchedEffect(Unit) { engine = vm.engine() }
    val all = remember(engine) {
        engine?.match(device.text, preferred = setOf(Category.TV, Category.STREAM, Category.PROJECTOR), limit = Int.MAX_VALUE).orEmpty()
            // devices that show up in screen-mirroring lists are TVs, projectors and streamers
            .filter { it.category in listOf(Category.TV, Category.STREAM, Category.PROJECTOR) }
    }
    val exact = all.firstOrNull()?.let { it.tier <= Tier.SAME_SERIES } == true
    val ranked = remember(all) {
        if (exact) all.filter { it.tier <= Tier.SAME_FAMILY }.take(40)
        else {
            // no model hit: skip near-duplicates; generation matches first, then modern smart-remote layouts, then full key sets
            val modern = setOf("home", "netflix", "primevideo", "prime", "source", "smarthub", "settings", "guide", "youtube", "disney")
            fun modernity(r: IrRemote) = r.buttons.count { norm(it.name) in modern }
            all.distinctBy { (it.remote.find(Slot.POWER)?.encoded ?: "") + it.remote.buttons.size }
                .sortedWith(compareBy<Match> { it.tier }.thenByDescending { modernity(it.remote) }.thenByDescending { it.remote.buttons.size })
                .take(25)
        }
    }
    val brands = remember(ranked) { ranked.map { it.category to it.remote.brand }.distinctBy { it.second }.take(3) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopBar(device.name, onBack = { vm.pop() }) },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Text(listOfNotNull(device.manufacturer, device.model, device.source).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            when {
                engine == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                ranked.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.device_no_match), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    if (!exact) {
                        Text(stringResource(R.string.device_no_exact, device.model ?: device.name), fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    brands.firstOrNull()?.let { (cat, brand) ->
                        Button(onClick = { vm.push(Screen.Wizard(cat, brand, device.text)) }, Modifier.fillMaxWidth().height(52.dp)) {
                            Text(stringResource(R.string.device_wizard_button, brand))
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        stringResource(if (exact) R.string.device_best_matches else R.string.device_other_remotes),
                        color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                    )
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(ranked) { m ->
                            MatchRow(m) {
                                val s = vm.addRemote(m.category, device.name.ifBlank { "${m.remote.brand} ${m.category.name}" }, m.remote)
                                vm.popToHome(); vm.push(Screen.Remote(s.id))
                            }
                        }
                    }
                }
            }
        }
    }
}
