package app.irremote.ir

/** A signal as stored in the database: either a protocol + address/command or raw timings. */
sealed interface IrSignal {
    data class Parsed(val protocol: String, val address: Long, val command: Long) : IrSignal
    class Raw(val frequency: Int, val dutyPercent: Int, val timings: IntArray) : IrSignal

    /** Key used to detect remotes that would send the same code (for auto-match de-duplication). */
    fun signature(): String = when (this) {
        is Parsed -> "$protocol:$address:$command"
        is Raw -> "raw:$frequency:${timings.contentHashCode()}"
    }

    companion object {
        /** Parses the compact on-disk form: `P:<proto>:<addr hex>:<cmd hex>` or `R:<freq>:<duty>:<t,t,t>`. */
        fun decode(s: String): IrSignal? {
            val p = s.split(':', limit = 4)
            return try {
                when (p[0]) {
                    "P" -> Parsed(p[1], p[2].toLong(16), p[3].toLong(16))
                    "R" -> Raw(p[1].toInt(), p[2].toInt(), p[3].split(',').map { it.toInt() }.toIntArray())
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        fun encode(s: IrSignal): String = when (s) {
            is Parsed -> "P:${s.protocol}:${s.address.toString(16)}:${s.command.toString(16)}"
            is Raw -> "R:${s.frequency}:${s.dutyPercent}:${s.timings.joinToString(",")}"
        }
    }
}

/** What `ConsumerIrManager.transmit` needs: carrier frequency and on/off durations (µs), starting with "on". */
class Transmission(val frequency: Int, val pattern: IntArray)
