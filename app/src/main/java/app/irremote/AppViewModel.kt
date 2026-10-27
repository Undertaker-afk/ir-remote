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
import app.irremote.ir.ac.AcProtocol
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
    data object Scan : Screen
    data object AutoScan : Screen
    data object Discover : Screen
    data class DeviceMatch(val device: FoundDevice) : Screen
    data class Brands(val category: Category) : Screen
    data class Wizard(val category: Category, val brand: String) : Screen
    data class Remote(val id: String) : Screen
}

/** One entry the wizard / code scan can offer: recorded keys from the database, or a generated A/C protocol. */
sealed interface Choice {
    class Keys(val c: Candidate) : Choice
    class Smart(val p: AcProtocol) : Choice
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

    // ---- in-app database update
    private val updater = DbUpdater(app)
    var dbInfo by mutableStateOf(updater.info())
        private set
    var dbBusy by mutableStateOf(false)
        private set
    var dbStatus by mutableStateOf<String?>(null)
        private set

    fun updateDatabase() {
        if (dbBusy) return
        dbBusy = true; dbStatus = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { updater.update { s -> dbStatus = s } }
            }
            dbBusy = false
            result.onSuccess { n ->
                db.clearCache(); everythingCache = null
                dbInfo = updater.info()
                dbStatus = "OK: $n"
            }.onFailure { dbStatus = "ERROR: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    fun resetDatabase() {
        updater.reset(); db.clearCache(); everythingCache = null; dbInfo = null; dbStatus = null
    }

    private var everythingCache: List<Pair<Category, IrRemote>>? = null

    /** Every bundled remote, for model search and the sticker scanner. */
    suspend fun everything(): List<Pair<Category, IrRemote>> = everythingCache ?: Category.entries.filter { it != Category.CUSTOM }
        .flatMap { c -> db.brands(c).flatMap { b -> b.remotes.map { c to it } } }.also { everythingCache = it }

    // ---- phone volume keys drive the open remote
    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var volumeKeys by mutableStateOf(prefs.getBoolean("volume_keys", false))
        private set

    fun toggleVolumeKeys(on: Boolean) {
        volumeKeys = on
        prefs.edit().putBoolean("volume_keys", on).apply()
    }

    /** @return true if the key was used (and must not change the phone volume). */
    fun handleVolumeKey(up: Boolean): Boolean {
        if (!volumeKeys) return false
        val id = (current as? Screen.Remote)?.id ?: return false
        val r = saved.firstOrNull { it.id == id } ?: return false
        val proto = AcProtocols.byId(r.acProtocol)
        if (proto != null) { // A/C: volume keys change the set temperature
            val old = r.acState ?: return false
            setAcState(id, old.copy(power = true, temp = (old.temp + if (up) 1 else -1).coerceIn(proto.minTemp, proto.maxTemp)))
            return true
        }
        val button = r.remote.find(if (up) Slot.VOL_UP else Slot.VOL_DOWN) ?: return false
        press(button)
        return true
    }

    fun setCustom(id: String, custom: List<CustomKey>) {
        val i = saved.indexOfFirst { it.id == id }
        if (i >= 0) { saved[i] = saved[i].copy(custom = custom); persist() }
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

    /** Every distinct power code in a category, most common first (A/C: generated protocols first); used by the automatic scan. */
    suspend fun scanChoices(category: Category, limit: Int = 800): List<Choice> = withContext(Dispatchers.Default) {
        val groups = LinkedHashMap<String, MutableList<Pair<IrRemote, IrButton>>>()
        for (b in db.brands(category)) for (r in b.remotes) {
            val btn = r.find(Slot.POWER) ?: continue
            val sig = btn.signal?.signature() ?: continue
            groups.getOrPut(sig) { ArrayList() }.add(r to btn)
        }
        val keys = groups.values.sortedByDescending { it.size }.map { g ->
            val best = g.maxBy { it.first.buttons.size }
            Choice.Keys(Candidate(best.first, best.second, g.size))
        }
        val smart = if (category == Category.AC) AcProtocols.all.map { Choice.Smart(it) } else emptyList()
        (smart + keys).take(limit)
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

    /** Imports a file the user picked (Flipper .ir, LIRC, irdb CSV, Pronto, raw). Returns the first new remote's id or null. */
    fun importFile(uri: Uri, displayName: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().take(8_000_000).toByteArray().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            importText(text.orEmpty(), displayName, onDone)
        }
    }

    /** Imports pasted or loaded text in any supported format; each remote found becomes a saved remote. */
    fun importText(text: String, name: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val remotes = withContext(Dispatchers.Default) { ImportFormats.parse(text, name) }
            if (remotes.isEmpty()) { onDone(null); return@launch }
            val added = remotes.mapIndexed { i, r ->
                val n = if (remotes.size == 1) name else r.model.ifBlank { "$name ${i + 1}" }
                addRemote(Category.CUSTOM, n, r)
            }
            onDone(added.first().id)
        }
    }

    // ---- rooms
    fun setRoom(id: String, room: String) {
        val i = saved.indexOfFirst { it.id == id }
        if (i >= 0) { saved[i] = saved[i].copy(room = room.trim()); persist() }
    }

    /** Sends "off" to every device in a room, one after the other. Single-button power remotes are toggled. */
    fun powerOffRoom(room: String) {
        val list = saved.filter { it.room == room }
        viewModelScope.launch {
            for (r in list) {
                val proto = AcProtocols.byId(r.acProtocol)
                if (proto != null) setAcState(r.id, (r.acState ?: AcState()).copy(power = false))
                else withContext(Dispatchers.Default) { r.remote.powerOffButton()?.let { transmitter.send(it.signal) } }
                kotlinx.coroutines.delay(450)
            }
        }
    }
}
