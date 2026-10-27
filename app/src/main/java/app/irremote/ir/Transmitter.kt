package app.irremote.ir

import android.content.Context
import android.hardware.ConsumerIrManager

/** Thin wrapper around the phone's built-in IR blaster. */
class Transmitter(context: Context) {
    private val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    val available: Boolean get() = manager?.hasIrEmitter() == true

    fun send(t: Transmission): Boolean {
        val m = manager ?: return false
        if (!m.hasIrEmitter()) return false
        return try { m.transmit(t.frequency, t.pattern); true } catch (e: Exception) { false }
    }

    /** @return false if the signal can't be encoded or the phone has no IR emitter. */
    fun send(signal: IrSignal?): Boolean {
        val m = manager ?: return false
        if (!m.hasIrEmitter() || signal == null) return false
        val t = IrEncoder.encode(signal) ?: return false
        return try {
            m.transmit(t.frequency, t.pattern)
            true
        } catch (e: Exception) {
            false
        }
    }
}
