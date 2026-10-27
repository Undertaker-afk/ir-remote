package app.irremote.data

import app.irremote.ir.IrSignal
import app.irremote.ir.ac.AcState

class IrButton(val name: String, val encoded: String) {
    val signal: IrSignal? by lazy { IrSignal.decode(encoded) }
}

class IrRemote(val brand: String, val model: String, val buttons: List<IrButton>) {
    fun find(slot: Slot): IrButton? {
        for (alias in slot.aliases) buttons.firstOrNull { norm(it.name) == alias }?.let { return it }
        return null
    }

    /** A dedicated "off" key if the remote has one, otherwise the (toggle) power key. */
    fun powerOffButton(): IrButton? {
        for (alias in listOf("poweroff", "off", "turnoff", "poweroffonly", "standby")) buttons.firstOrNull { norm(it.name) == alias }?.let { return it }
        return find(Slot.POWER)
    }

    /** Buttons that don't belong to a fixed slot on the main remote layout (shown in the "more keys" sheet). */
    fun extraButtons(): List<IrButton> {
        val used = Slot.entries.flatMap { s -> find(s)?.let { listOf(it) } ?: emptyList() }.toSet()
        return buttons.filter { it !in used }
    }
}

/** Device categories. [dir] is the asset file name; the grid mirrors the device kinds known from Mi Remote plus extras. */
enum class Category(val dir: String) {
    TV("tv"), SETTOP("settop"), AC("ac"), FAN("fan"), STREAM("stream"), AVR("avr"), DVD("dvd"),
    PROJECTOR("projector"), CAMERA("camera"), LIGHT("light"), CLEANER("cleaner"), COMPUTER("computer"), CUSTOM("custom");

    companion object {
        fun fromDir(d: String) = entries.firstOrNull { it.dir == d } ?: CUSTOM
    }
}

fun norm(s: String): String = s.lowercase().replace("+", "plus").replace("-", "minus").filter { it.isLetterOrDigit() }

/** Fixed positions on the main remote layout; each lists the (normalised) button names that fill it, best first. */
enum class Slot(vararg names: String) {
    POWER("power", "pwr", "powertoggle", "poweronoff", "onoff", "standby", "stby", "poweron", "poweroff", "on", "off"),
    INPUT("input", "source", "inputsource", "sources", "tvav", "av", "extinput"),
    MENU("menu", "setup", "settings", "options", "tools"),
    HOME("home", "smarthub", "hub"),
    BACK("back", "return", "exit", "esc"),
    OK("ok", "enter", "select", "okselect", "center", "confirm"),
    UP("up", "arrowup", "cursorup", "navup"),
    DOWN("down", "arrowdown", "cursordown", "navdown"),
    LEFT("left", "arrowleft", "cursorleft", "navleft"),
    RIGHT("right", "arrowright", "cursorright", "navright"),
    VOL_UP("volup", "volumeup", "volplus", "volumeplus", "volumeinc", "vol"),
    VOL_DOWN("voldown", "voldn", "volumedown", "volminus", "volumeminus", "volumedec"),
    CH_UP("chup", "chnext", "channelup", "chplus", "channelplus", "pageup", "chnxt"),
    CH_DOWN("chdown", "chdn", "chprev", "channeldown", "chminus", "channelminus", "pagedown"),
    MUTE("mute", "muting"),
    D0("0", "num0", "number0", "digit0", "key0"), D1("1", "num1", "number1", "digit1", "key1"),
    D2("2", "num2", "number2", "digit2", "key2"), D3("3", "num3", "number3", "digit3", "key3"),
    D4("4", "num4", "number4", "digit4", "key4"), D5("5", "num5", "number5", "digit5", "key5"),
    D6("6", "num6", "number6", "digit6", "key6"), D7("7", "num7", "number7", "digit7", "key7"),
    D8("8", "num8", "number8", "digit8", "key8"), D9("9", "num9", "number9", "digit9", "key9");

    val aliases: List<String> = names.map { norm(it) }
    val repeats get() = this in setOf(UP, DOWN, LEFT, RIGHT, VOL_UP, VOL_DOWN, CH_UP, CH_DOWN)

    companion object {
        val digits = listOf(D1, D2, D3, D4, D5, D6, D7, D8, D9, D0)
    }
}

/** A remote the user has added; self-contained so it keeps working if the bundled database changes. */
data class SavedRemote(
    val id: String,
    val category: Category,
    val name: String,
    val remote: IrRemote,
    /** Set for "smart" A/C remotes that generate whole-state frames instead of replaying recorded keys. */
    val acProtocol: String? = null,
    val acState: AcState? = null,
    /** User-built layout: chosen buttons in order, with optional renamed labels. Empty = none. */
    val custom: List<CustomKey> = emptyList(),
    /** Room this device belongs to ("" = none); rooms group devices on the home screen. */
    val room: String = "",
)

/** One key on a user's custom layout; [button] is the original button name, [label] what the user sees. */
data class CustomKey(val button: String, val label: String)
