package app.irremote.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.irremote.ir.ac.AcState
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

class Brand(val name: String, val remotes: List<IrRemote>)

/** Bundled Flipper-IRDB data (see tools/build_db.py), loaded one category at a time. */
class Database(private val context: Context) {
    private val cache = HashMap<Category, List<Brand>>()

    fun clearCache() { synchronized(cache) { cache.clear() } }

    suspend fun brands(category: Category): List<Brand> = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[category] }?.let { return@withContext it }
        val loaded = load(category)
        synchronized(cache) { cache[category] = loaded }
        loaded
    }

    private fun load(category: Category): List<Brand> {
        val remotes = ArrayList<IrRemote>()
        var brand = ""
        var model = ""
        var buttons = ArrayList<IrButton>()
        fun flush() {
            if (brand.isNotEmpty() && buttons.isNotEmpty()) remotes.add(IrRemote(brand, model, buttons))
            buttons = ArrayList()
        }
        // an in-app update (see DbUpdater) takes precedence over the data bundled in the APK
        val updated = File(context.filesDir, "db/${category.dir}.txt")
        val input = if (updated.exists()) updated.inputStream() else context.assets.open("db/${category.dir}.txt")
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("@")) {
                    flush()
                    val t = line.substring(1).split('\t', limit = 2)
                    brand = t[0]
                    model = t.getOrElse(1) { "" }
                } else {
                    val t = line.split('\t', limit = 2)
                    if (t.size == 2) buttons.add(IrButton(t[0], t[1]))
                }
            }
        }
        flush()
        return remotes.groupBy { it.brand }
            .map { Brand(it.key, it.value) }
            .sortedBy { it.name.lowercase() }
    }
}

/** User's saved remotes, stored as text in the app's private files dir (same line format as the bundled data). */
class SavedStore(context: Context) {
    private val file = File(context.filesDir, "remotes.txt")

    fun load(): List<SavedRemote> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<SavedRemote>()
        var head: List<String>? = null
        var brand = ""
        var buttons = ArrayList<IrButton>()
        fun flush() {
            val h = head ?: return
            out.add(SavedRemote(h[0], Category.fromDir(h[1]), h[2], IrRemote(brand, h.getOrElse(3) { "" }, buttons), h.getOrNull(5)?.ifEmpty { null }, AcState.parse(h.getOrNull(6)), decodeCustom(h.getOrNull(7)), h.getOrElse(8) { "" }))
            buttons = ArrayList()
        }
        file.forEachLine { line ->
            if (line.startsWith("@@")) {
                flush()
                head = line.substring(2).split('\t')
                brand = head!!.getOrElse(4) { "" }
            } else {
                val t = line.split('\t', limit = 2)
                if (t.size == 2) buttons.add(IrButton(t[0], t[1]))
            }
        }
        flush()
        return out
    }

    fun save(list: List<SavedRemote>) {
        val tmp = File(file.parentFile, "remotes.txt.tmp")
        tmp.bufferedWriter().use { w ->
            for (s in list) {
                w.write("@@${s.id}\t${s.category.dir}\t${s.name.clean()}\t${s.remote.model.clean()}\t${s.remote.brand.clean()}\t${s.acProtocol ?: ""}\t${s.acState?.serialize() ?: ""}\t${encodeCustom(s.custom)}\t${s.room.clean()}\n")
                for (b in s.remote.buttons) w.write("${b.name.clean()}\t${b.encoded}\n")
            }
        }
        tmp.renameTo(file)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    private fun encodeCustom(l: List<CustomKey>) = l.joinToString(";") { "${enc(it.button)}|${enc(it.label)}" }
    private fun decodeCustom(s: String?): List<CustomKey> =
        if (s.isNullOrEmpty()) emptyList() else s.split(';').mapNotNull { e ->
            e.split('|').takeIf { it.size == 2 }?.let { CustomKey(dec(it[0]), dec(it[1])) }
        }

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ')
}
