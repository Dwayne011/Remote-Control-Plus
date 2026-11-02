package dev.amrakshay.atvremote.protocol

import java.io.ByteArrayOutputStream

/**
 * Both ports frame every protobuf message as `[varint length][message bytes]`.
 *
 * A single socket read may deliver a partial varint, exactly one message,
 * several messages, or a message cut anywhere in the middle — including inside
 * the length varint itself. Anything that reads one message per `read()` works
 * on a quiet LAN and falls apart on real Wi-Fi, so this is a buffering loop.
 */
class FrameReader {
    private var pending = ByteArray(0)

    /** Feed raw socket bytes; returns every complete message now available. */
    fun add(data: ByteArray, length: Int = data.size): List<ByteArray> {
        pending = pending + data.copyOf(length)
        val out = mutableListOf<ByteArray>()
        var offset = 0
        while (true) {
            val header = readVarint(pending, offset) ?: break
            val (messageLength, bodyStart) = header
            if (pending.size - bodyStart < messageLength) break
            out += pending.copyOfRange(bodyStart, bodyStart + messageLength)
            offset = bodyStart + messageLength
        }
        if (offset > 0) pending = pending.copyOfRange(offset, pending.size)
        return out
    }

    companion object {
        /** Returns value and the index just past the varint, or null if incomplete. */
        fun readVarint(buf: ByteArray, start: Int): Pair<Int, Int>? {
            var result = 0
            var shift = 0
            var i = start
            while (true) {
                if (i >= buf.size) return null
                val byte = buf[i++].toInt() and 0xFF
                result = result or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) return result to i
                shift += 7
                require(shift <= 35) { "varint too long" }
            }
        }

        fun encodeVarint(value: Int): ByteArray {
            val out = ByteArrayOutputStream()
            var v = value
            while (v > 0x7F) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v)
            return out.toByteArray()
        }

        /** Frame a serialized message for sending. */
        fun frame(message: ByteArray): ByteArray = encodeVarint(message.size) + message
    }
}
