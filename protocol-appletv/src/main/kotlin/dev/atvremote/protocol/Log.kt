package dev.atvremote.protocol

/**
 * Minimal wire logging, enabled with ATV_DEBUG=1 or by setting [enabled].
 *
 * Protocol faults here are usually silent (a frame that fails to decode simply
 * never resolves its waiter), so being able to see raw frames is the difference
 * between a diagnosis and a guess.
 *
 * Local change (Remote Control Plus): [sink] lets the host app capture lines
 * instead of System.err, so they appear in its in-app diagnostics log.
 */
object Log {
    @Volatile
    var enabled: Boolean = System.getenv("ATV_DEBUG") == "1"

    @Volatile
    var sink: ((String) -> Unit)? = null

    fun d(message: () -> String) {
        if (!enabled) return
        val text = message()
        sink?.invoke(text) ?: System.err.println("[atv] $text")
    }

    fun hex(data: ByteArray, limit: Int = 96): String {
        val shown = data.take(limit).joinToString("") { "%02x".format(it) }
        return if (data.size > limit) "$shown... (${data.size} bytes)" else "$shown (${data.size} bytes)"
    }
}
