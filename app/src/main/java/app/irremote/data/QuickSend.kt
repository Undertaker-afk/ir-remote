package app.irremote.data

import android.content.Context
import app.irremote.ir.Transmitter
import app.irremote.ir.ac.AcProtocols

/** Headless "send one key" used by shortcuts, `adb`/Tasker intents and assistant routines. */
object QuickSend {
    /**
     * @param remote saved remote id or (case-insensitive) name
     * @param key a remote slot ("power", "vol_up", "mute", "ok", "5" ...) or an exact button name;
     *   for smart A/C remotes: power, power_on, power_off, temp_up, temp_down
     * @return null on success, otherwise a short reason
     */
    fun run(context: Context, remote: String?, key: String?): String? {
        if (remote.isNullOrBlank() || key.isNullOrBlank()) return "missing remote or key"
        val store = SavedStore(context)
        val all = store.load()
        val target = all.firstOrNull { it.id == remote } ?: all.firstOrNull { it.name.equals(remote, ignoreCase = true) }
            ?: all.firstOrNull { it.name.contains(remote, ignoreCase = true) }
            ?: return "no remote named \"$remote\""
        val tx = Transmitter(context)
        if (!tx.available) return "no IR emitter"

        val proto = AcProtocols.byId(target.acProtocol)
        if (proto != null) {
            val old = target.acState ?: return "no state"
            val new = when (norm(key)) {
                "poweron", "on" -> old.copy(power = true)
                "poweroff", "off" -> old.copy(power = false)
                "power", "toggle", "powertoggle" -> old.copy(power = !old.power)
                "tempup", "temperatureup" -> old.copy(power = true, temp = (old.temp + 1).coerceAtMost(proto.maxTemp))
                "tempdown", "temperaturedown" -> old.copy(power = true, temp = (old.temp - 1).coerceAtLeast(proto.minTemp))
                else -> return "unknown A/C key \"$key\""
            }
            if (!tx.send(proto.encode(new))) return "send failed"
            store.save(all.map { if (it.id == target.id) it.copy(acState = new) else it })
            return null
        }

        val n = norm(key)
        val slot = Slot.entries.firstOrNull { n in it.aliases || n == norm(it.name) }
        val button = slot?.let { target.remote.find(it) } ?: target.remote.buttons.firstOrNull { norm(it.name) == n }
            ?: return "remote has no key \"$key\""
        return if (tx.send(button.signal)) null else "send failed"
    }
}
