package app.irremote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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

/** Search every bundled remote by brand or model number and add the exact one without the wizard. */
@Composable
fun SearchScreen(vm: AppViewModel) {
    var index by remember { mutableStateOf<List<Triple<Category, IrRemote, String>>?>(null) }
    LaunchedEffect(Unit) { index = vm.everything().map { (c, r) -> Triple(c, r, "${r.brand} ${r.model}".lowercase()) } }
    var query by remember { mutableStateOf("") }
    val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
    val results = remember(index, query) {
        if (terms.isEmpty()) emptyList()
        else index.orEmpty().filter { (_, _, text) -> terms.all { it in text } }.take(100)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopBar(stringResource(R.string.search_models), onBack = { vm.pop() }) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                query, { query = it }, singleLine = true, shape = CircleShape,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            when {
                index == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                terms.isNotEmpty() && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_results), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                    items(results) { (cat, remote, _) ->
                        val title = "${remote.brand} ${remote.model.removePrefix(remote.brand).trim()}".trim()
                        val catLabel = cat.label()
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                val s = vm.addRemote(cat, "${remote.brand} $catLabel", remote)
                                vm.popToHome(); vm.push(Screen.Remote(s.id))
                            }.padding(vertical = 12.dp)
                        ) {
                            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "$catLabel · ${stringResource(R.string.keys_count, remote.buttons.size)}",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
