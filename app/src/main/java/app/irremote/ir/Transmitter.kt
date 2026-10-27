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
        return try { m.transmit(supportedFrequency(m, t.frequency), t.pattern); true } catch (e: Exception) { false }
    }

    /** Some protocols use carriers (e.g. 57.6 kHz) outside what a phone's emitter reports; use the closest it supports. */
    private fun supportedFrequency(m: ConsumerIrManager, wanted: Int): Int {
        val ranges = m.carrierFrequencies ?: return wanted
        if (ranges.isEmpty() || ranges.any { wanted in it.minFrequency..it.maxFrequency }) return wanted
        return ranges.flatMap { listOf(it.minFrequency, it.maxFrequency) }.minBy { kotlin.math.abs(it - wanted) }
    }

    /** @return false if the signal can't be encoded or the phone has no IR emitter. */
    fun send(signal: IrSignal?): Boolean {
        val m = manager ?: return false
        if (!m.hasIrEmitter() || signal == null) return false
        return send(IrEncoder.encode(signal) ?: return false)
    }
}
