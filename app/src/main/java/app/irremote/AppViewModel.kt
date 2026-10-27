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
import app.irremote.ir.ac.AcProtocols
import app.irremote.ir.ac.AcState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data object Categories : Screen
    data object Search : Screen
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

    private var loaded = false
    private var pendingOpen: String? = null

    init {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.load() }
            saved.addAll(list)
            loaded = true
            publishShortcuts()
            pendingOpen?.let { openRemote(it) }
        }
    }

    /** Opens a saved remote (from a launcher shortcut); waits for the list to load if needed. */
    fun openRemote(id: String) {
        if (!loaded) { pendingOpen = id; return }
        pendingOpen = null
        if (saved.any { it.id == id } && (current as? Screen.Remote)?.id != id) { popToHome(); push(Screen.Remote(id)) }
    }

    private fun publishShortcuts() = Shortcuts.publish(getApplication(), saved.toList())

    /** Every bundled remote, for model search. */
    suspend fun everything(): List<Pair<Category, IrRemote>> =
        Category.entries.filter { it != Category.CUSTOM }.flatMap { c -> db.brands(c).flatMap { b -> b.remotes.map { c to it } } }

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

    fun addAcRemote(name: String, protocol: String): SavedRemote {
        val s = SavedRemote(UUID.randomUUID().toString(), Category.AC, name, IrRemote(name, "", emptyList()), protocol, AcState(power = true))
        saved.add(s)
        persist()
        return s
    }

    /** Stores the new A/C state and sends the full frame (only when switched on, or when switching off). */
    fun setAcState(id: String, state: AcState) {
        val i = saved.indexOfFirst { it.id == id }
        if (i < 0) return
        val o = saved[i]
        saved[i] = o.copy(acState = state)
        persist()
        val proto = AcProtocols.byId(o.acProtocol) ?: return
        if (state.power || o.acState?.power == true) {
            if (!transmitter.send(proto.encode(state))) message = "send"
        }
    }

    fun delete(id: String) {
        saved.removeAll { it.id == id }
        persist()
    }

    fun rename(id: String, name: String) {
        val i = saved.indexOfFirst { it.id == id }
        if (i >= 0 && name.isNotBlank()) {
            val o = saved[i]
            saved[i] = o.copy(name = name.trim())
            persist()
        }
    }

    private fun persist() {
        val snapshot = saved.toList()
        viewModelScope.launch(Dispatchers.IO) { store.save(snapshot) }
        publishShortcuts()
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
