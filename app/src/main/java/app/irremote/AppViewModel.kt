package app.irremote

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.irremote.data.*
import app.irremote.ir.Transmitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data object Categories : Screen
    data class Brands(val category: Category) : Screen
    data class Wizard(val category: Category, val brand: String) : Screen
    data class Remote(val id: String) : Screen
}

/** One distinct configuration offered by the auto-match wizard: remotes sharing the same test code collapse into one. */
class Candidate(val remote: IrRemote, val testButton: IrButton, val sharedBy: Int)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Database(app)
    private val store = SavedStore(app)
    val transmitter = Transmitter(app)

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val saved = mutableStateListOf<SavedRemote>()
    var message by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.load() }
            saved.addAll(list)
        }
    }

    val current: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop(): Boolean { if (stack.size > 1) { stack.removeAt(stack.size - 1); return true }; return false }
    fun popToHome() { while (stack.size > 1) stack.removeAt(stack.size - 1) }

    suspend fun brands(c: Category) = db.brands(c)

    fun press(button: IrButton?): Boolean {
        val ok = transmitter.send(button?.signal)
        if (!ok && button != null) message = "send"
        return ok
    }

    /** Candidates for a brand, most common first. [testSlots] lists which key to test with, in preference order. */
    fun candidates(brand: Brand, testSlots: List<Slot>, limit: Int = 15): List<Candidate> {
        for (slot in testSlots) {
            val groups = LinkedHashMap<String, MutableList<Pair<IrRemote, IrButton>>>()
            for (r in brand.remotes) {
                val b = r.find(slot) ?: continue
                val sig = b.signal?.signature() ?: continue
                groups.getOrPut(sig) { ArrayList() }.add(r to b)
            }
            if (groups.isEmpty()) continue
            return groups.values.sortedByDescending { it.size }.take(limit).map { g ->
                val best = g.maxBy { it.first.buttons.size }
                Candidate(best.first, best.second, g.size)
            }
        }
        return emptyList()
    }

    fun addRemote(category: Category, name: String, remote: IrRemote): SavedRemote {
        val s = SavedRemote(UUID.randomUUID().toString(), category, name, remote)
        saved.add(s)
        persist()
        return s
    }

    fun delete(id: String) {
        saved.removeAll { it.id == id }
        persist()
    }

    fun rename(id: String, name: String) {
        val i = saved.indexOfFirst { it.id == id }
        if (i >= 0 && name.isNotBlank()) {
            val o = saved[i]
            saved[i] = SavedRemote(o.id, o.category, name.trim(), o.remote)
            persist()
        }
    }

    private fun persist() {
        val snapshot = saved.toList()
        viewModelScope.launch(Dispatchers.IO) { store.save(snapshot) }
    }

    /** Imports a Flipper .ir file picked by the user. Returns the new remote's id or null if nothing could be read. */
    fun importFile(uri: Uri, displayName: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val remote = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                }.getOrNull()?.let { FlipperIr.parse(it, "", displayName) }
            }
            if (remote == null || remote.buttons.isEmpty()) onDone(null)
            else onDone(addRemote(Category.CUSTOM, displayName, remote).id)
        }
    }
}
