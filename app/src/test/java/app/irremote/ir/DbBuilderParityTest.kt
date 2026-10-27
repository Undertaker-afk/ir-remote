package app.irremote.ir

import app.irremote.data.Category
import app.irremote.data.DbBuilder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Parity with tools/build_db.py. Runs only when IR_PARITY_DIR points at a directory with flipper.zip, irdb.zip
 * (git archive snapshots) and expected/<category>.txt produced by the Python builder.
 */
class DbBuilderParityTest {
    private fun remotes(text: String): Map<String, Int> {
        // multiset of "brand\tmodel\tbutton\tsignal..." blocks, order independent
        val out = HashMap<String, Int>()
        var head = ""; val body = ArrayList<String>()
        fun flush() { if (head.isNotEmpty()) out.merge(head + "\n" + body.joinToString("\n"), 1, Int::plus); body.clear() }
        for (l in text.lineSequence()) if (l.startsWith("@")) { flush(); head = l } else if (l.isNotEmpty()) body.add(l)
        flush()
        return out
    }

    @Test fun kotlinBuilderMatchesPythonBuilder() {
        val dir = System.getenv("IR_PARITY_DIR")
        assumeTrue(dir != null && File(dir, "flipper.zip").exists())
        val r = File(dir, "flipper.zip").inputStream().use { f -> File(dir, "irdb.zip").inputStream().use { i -> DbBuilder.build(f, i) } }
        File(dir, "actual").apply { mkdirs() }.let { a -> r.files.forEach { (c, t) -> File(a, "${c.dir}.txt").writeText(t) } }
        for (cat in Category.entries.filter { it != Category.CUSTOM }) {
            val expected = remotes(File(dir, "expected/${cat.dir}.txt").readText())
            val actual = remotes(r.files.getValue(cat))
            val missing = expected.keys - actual.keys
            val extra = actual.keys - expected.keys
            assertTrue("${cat.dir}: ${missing.size} missing, ${extra.size} extra; e.g. ${missing.firstOrNull()?.take(300)} | ${extra.firstOrNull()?.take(300)}", missing.isEmpty() && extra.isEmpty())
            assertEquals("remote count for ${cat.dir}", expected.values.sum(), actual.values.sum())
        }
    }
}
