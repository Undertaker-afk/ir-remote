package app.irremote.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
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
import androidx.core.content.FileProvider
import app.irremote.AppViewModel
import app.irremote.R
import app.irremote.Screen
import app.irremote.data.Category
import app.irremote.data.IrRemote
import app.irremote.data.ModelMatcher
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/** "Find my code": photograph the model sticker (or pick a photo), read it on-device and rank matching remotes. */
@Composable
fun ScanScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var index by remember { mutableStateOf<List<Triple<Category, IrRemote, String>>?>(null) }
    LaunchedEffect(Unit) { index = vm.everything().map { (c, r) -> Triple(c, r, "${r.brand} ${r.model}") } }

    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun recognize(uri: Uri) {
        busy = true; failed = false
        try {
            val image = InputImage.fromFilePath(context, uri)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                .addOnSuccessListener { r -> text = ModelMatcher.tokens(r.text).take(12).joinToString(" "); busy = false }
                .addOnFailureListener { busy = false; failed = true }
        } catch (e: Exception) { busy = false; failed = true }
    }

    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) photoUri?.let(::recognize) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) recognize(uri) }

    val tokens = remember(text) { ModelMatcher.tokens(text) }
    val results = remember(index, tokens) {
        if (tokens.isEmpty()) emptyList()
        else index.orEmpty().map { it to ModelMatcher.score(it.second.brand, it.second.model, tokens) }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(60).map { it.first }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopBar(stringResource(R.string.scan_title), onBack = { vm.pop() }) },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.scan_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val dir = File(context.cacheDir, "scan").apply { mkdirs() }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "sticker.jpg"))
                    photoUri = uri
                    camera.launch(uri)
                }, Modifier.weight(1f)) {
                    Icon(Icons.Filled.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.take_photo))
                }
                OutlinedButton(onClick = { gallery.launch("image/*") }, Modifier.weight(1f)) {
                    Icon(Icons.Filled.Image, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.pick_photo))
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                text, { text = it }, shape = CircleShape, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.scan_recognized)) },
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (failed) Text(stringResource(R.string.scan_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            if (tokens.isNotEmpty() && results.isEmpty() && index != null && !busy) {
                Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_results), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(results) { (cat, remote, _) ->
                    val catLabel = cat.label()
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            val s = vm.addRemote(cat, "${remote.brand} $catLabel", remote)
                            vm.popToHome(); vm.push(Screen.Remote(s.id))
                        }.padding(vertical = 12.dp)
                    ) {
                        Text("${remote.brand} ${remote.model.removePrefix(remote.brand).trim()}".trim(), fontSize = 17.sp, fontWeight = FontWeight.Medium)
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
