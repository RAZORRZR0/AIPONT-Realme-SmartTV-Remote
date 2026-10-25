package dev.tvremote.ir

import android.content.Context
import android.hardware.ConsumerIrManager
import android.util.Log
import dev.tvremote.core.RemoteKey
import dev.tvremote.core.RemoteTransport
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** NEC, address [addr], LSB-first, 562/1687 µs bits, padded to a 110 ms frame, then [repeats] repeat bursts. */
fun nec(cmd: Int, addr: Int = 0x40, repeats: Int = 1): IntArray {
    fun rev(v: Int) = Integer.reverse(v and 0xFF) ushr 24
    fun byteBits(v: Int) = (7 downTo 0).flatMap { listOf(562, if ((v shr it) and 1 == 1) 1687 else 562) }
    val a = rev(addr)
    val c = rev(cmd)
    val p = ArrayList<Int>().apply {
        add(9000); add(4500)
        addAll(byteBits(a)); addAll(byteBits(a.inv() and 0xFF))
        addAll(byteBits(c)); addAll(byteBits(c.inv() and 0xFF))
        add(562)
        add(110000 - sum())
        repeat(repeats) { addAll(listOf(9000, 2250, 562, 98188)) }
    }
    return p.toIntArray()
}

class IrRemote(context: Context) : RemoteTransport {
    private val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    private val io = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)

    /** True while a transmit is queued or running. */
    val busy: Boolean get() = pending.get() > 0

    override val available = ir?.hasIrEmitter() == true

    override fun send(key: RemoteKey) {
        val m = ir ?: return
        pending.incrementAndGet()
        io.execute {
            try { m.transmit(38000, nec(key.necCmd)) }
            catch (e: Exception) { Log.d("TvRemote", "IR transmit failed: ${e.message}") }
            finally { pending.decrementAndGet() }
        }
    }
}
