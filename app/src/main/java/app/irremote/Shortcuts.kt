package app.irremote

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.irremote.data.SavedRemote

/** Long-press launcher shortcuts: open / power for the most recent remotes. Assistant can launch these by name. */
object Shortcuts {
    fun publish(context: Context, remotes: List<SavedRemote>) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4)
        val icon = IconCompat.createWithResource(context, R.drawable.ic_launcher_fg)
        val list = ArrayList<ShortcutInfoCompat>()
        for (r in remotes.takeLast(2).reversed()) {
            list.add(
                ShortcutInfoCompat.Builder(context, "power_${r.id}")
                    .setShortLabel(context.getString(R.string.shortcut_power, r.name))
                    .setIcon(icon)
                    .setIntent(
                        Intent(context, SendActivity::class.java).setAction("app.irremote.SEND")
                            .putExtra("remote", r.id).putExtra("key", "power")
                    ).build()
            )
            list.add(
                ShortcutInfoCompat.Builder(context, "open_${r.id}")
                    .setShortLabel(r.name)
                    .setIcon(icon)
                    .setIntent(
                        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("remote_id", r.id)
                    ).build()
            )
        }
        try { ShortcutManagerCompat.setDynamicShortcuts(context, list.take(max)) } catch (_: Exception) { }
    }
}
