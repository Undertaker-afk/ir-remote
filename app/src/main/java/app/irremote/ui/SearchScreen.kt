package app.irremote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.irremote.AppViewModel
import app.irremote.R
import app.irremote.Screen
import app.irremote.data.Category
import app.irremote.data.IrRemote
import app.irremote.data.Match
import app.irremote.data.MatchEngine
import app.irremote.data.Tier

/** Search by brand, model number or remote part number; the matching engine ranks series/revision matches first. */
@Composable
fun SearchScreen(vm: AppViewModel) {
    var engine by remember { mutableStateOf<MatchEngine?>(null) }
    var index by remember { mutableStateOf<List<Triple<Category, IrRemote, String>>?>(null) }
    LaunchedEffect(Unit) {
        engine = vm.engine()
        index = vm.everything().map { (c, r) -> Triple(c, r, "${r.brand} ${r.model}".lowercase()) }
    }
    var query by remember { mutableStateOf("") }
    val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
    val results = remember(engine, index, query) {
        if (terms.isEmpty() || engine == null) emptyList()
        else {
            val ranked = engine!!.match(query, preferred = setOf(Category.TV, Category.STREAM, Category.PROJECTOR), limit = 100)
            // partial words ("sams", "crystal") are not model numbers; fall back to plain substring search
            ranked.ifEmpty {
                index.orEmpty().filter { (_, _, text) -> terms.all { it in text } }.take(100).map { (c, r, _) -> Match(c, r, Tier.BRAND, 0, "") }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopBar(stringResource(R.string.search_models), onBack = { vm.pop() }) {
                IconButton(onClick = { vm.push(Screen.Scan) }) { Icon(Icons.Filled.CameraAlt, stringResource(R.string.scan_title)) }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                query, { query = it }, singleLine = true, shape = CircleShape,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            when {
                engine == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                terms.isNotEmpty() && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_results), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                    items(results) { m ->
                        val catLabel = m.category.label()
                        MatchRow(m) {
                            val s = vm.addRemote(m.category, "${m.remote.brand} $catLabel", m.remote)
                            vm.popToHome(); vm.push(Screen.Remote(s.id))
                        }
                    }
                }
            }
        }
    }
}
