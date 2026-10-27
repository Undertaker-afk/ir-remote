package app.irremote.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Downloads fresh Flipper-IRDB and irdb snapshots, rebuilds the category files and swaps them in atomically. */
class DbUpdater(private val context: Context) {
    private val dir get() = File(context.filesDir, "db")

    /** "date|remotes" of the installed update, or null while the bundled data is in use. */
    fun info(): String? = File(dir, "info.txt").takeIf { it.exists() }?.readText()

    fun reset() { dir.deleteRecursively() }

    /** @return the number of remotes in the new database. Throws on any failure and leaves the old data untouched. */
    fun update(onProgress: (String) -> Unit): Int {
        val flipper = File(context.cacheDir, "flipper.zip")
        val irdb = File(context.cacheDir, "irdb.zip")
        try {
            onProgress("Flipper-IRDB…"); download(FLIPPER, flipper)
            onProgress("irdb…"); download(IRDB, irdb)
            onProgress("…")
            val result = flipper.inputStream().use { f -> irdb.inputStream().use { i -> DbBuilder.build(f, i) } }
            if (result.total < 500) throw IllegalStateException("downloaded data looks incomplete (${result.total} remotes)")
            val fresh = File(context.filesDir, "db_new").apply { deleteRecursively(); mkdirs() }
            for ((cat, text) in result.files) File(fresh, "${cat.dir}.txt").writeText(text)
            File(fresh, "info.txt").writeText("${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}|${result.total}")
            val old = File(context.filesDir, "db_old").apply { deleteRecursively() }
            if (dir.exists()) dir.renameTo(old)
            if (!fresh.renameTo(dir)) { old.renameTo(dir); throw IllegalStateException("could not install update") }
            old.deleteRecursively()
            return result.total
        } finally {
            flipper.delete(); irdb.delete()
        }
    }

    private fun download(url: String, to: File) {
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        var hops = 0
        while (conn.responseCode in 300..399 && hops++ < 4) {
            val next = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(next).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        }
        if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode} for $url")
        conn.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
    }

    companion object {
        const val FLIPPER = "https://codeload.github.com/Lucaslhm/Flipper-IRDB/zip/refs/heads/main"
        const val IRDB = "https://codeload.github.com/probonopd/irdb/zip/refs/heads/master"
    }
}
