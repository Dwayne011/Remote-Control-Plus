package com.remotecontrolplus.cast

import java.io.ByteArrayOutputStream

/**
 * The single protobuf message type on a Cast v2 socket.
 *
 * ```
 * message CastMessage {
 *   enum ProtocolVersion { CASTV2_1_0 = 0; }
 *   required ProtocolVersion protocol_version = 1;
 *   required string source_id = 2;
 *   required string destination_id = 3;
 *   required string namespace = 4;
 *   enum PayloadType { STRING = 0; BINARY = 1; }
 *   required PayloadType payload_type = 5;
 *   optional string payload_utf8 = 6;
 *   optional bytes payload_binary = 7;
 * }
 * ```
 *
 * Seven fields is not enough to justify protoc in the build, so the wire
 * format is read and written by hand here.
 */
data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String? = null,
    val payloadBinary: ByteArray? = null,
    val protocolVersion: Int = 0,
) {
    val isBinary: Boolean get() = payloadBinary != null

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeVarintField(1, protocolVersion.toLong())
        out.writeStringField(2, sourceId)
        out.writeStringField(3, destinationId)
        out.writeStringField(4, namespace)
        out.writeVarintField(5, if (isBinary) 1L else 0L)
        payloadUtf8?.let { out.writeStringField(6, it) }
        payloadBinary?.let { out.writeBytesField(7, it) }
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CastMessage) return false
        return sourceId == other.sourceId && destinationId == other.destinationId &&
            namespace == other.namespace && payloadUtf8 == other.payloadUtf8 &&
            protocolVersion == other.protocolVersion &&
            (payloadBinary?.contentEquals(other.payloadBinary) ?: (other.payloadBinary == null))
    }

    override fun hashCode(): Int =
        listOf(sourceId, destinationId, namespace, payloadUtf8, protocolVersion).hashCode() * 31 +
            (payloadBinary?.contentHashCode() ?: 0)

    companion object {
        fun decode(bytes: ByteArray): CastMessage {
            var pos = 0
            var version = 0
            var source = ""
            var destination = ""
            var namespace = ""
            var payloadType = 0L
            var utf8: String? = null
            var binary: ByteArray? = null

            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    require(pos < bytes.size) { "truncated varint" }
                    val b = bytes[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    require(shift < 64) { "varint too long" }
                }
            }

            fun readBytes(): ByteArray {
                val len = readVarint().toInt()
                require(len >= 0 && pos + len <= bytes.size) { "truncated length-delimited field" }
                return bytes.copyOfRange(pos, pos + len).also { pos += len }
            }

            while (pos < bytes.size) {
                val tag = readVarint()
                val field = (tag ushr 3).toInt()
                when (val wireType = (tag and 0x7).toInt()) {
                    0 -> {
                        val v = readVarint()
                        when (field) {
                            1 -> version = v.toInt()
                            5 -> payloadType = v
                        }
                    }
                    2 -> {
                        val data = readBytes()
                        when (field) {
                            2 -> source = String(data, Charsets.UTF_8)
                            3 -> destination = String(data, Charsets.UTF_8)
                            4 -> namespace = String(data, Charsets.UTF_8)
                            6 -> utf8 = String(data, Charsets.UTF_8)
                            7 -> binary = data
                        }
                    }
                    1 -> pos += 8
                    5 -> pos += 4
                    else -> throw IllegalArgumentException("unsupported wire type $wireType")
                }
            }
            return CastMessage(
                sourceId = source,
                destinationId = destination,
                namespace = namespace,
                payloadUtf8 = if (payloadType == 0L) (utf8 ?: "") else utf8,
                payloadBinary = if (payloadType == 1L) (binary ?: ByteArray(0)) else binary,
                protocolVersion = version,
            )
        }

        private fun ByteArrayOutputStream.writeVarint(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    write(v.toInt())
                    return
                }
                write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }

        private fun ByteArrayOutputStream.writeVarintField(field: Int, value: Long) {
            writeVarint(((field shl 3) or 0).toLong())
            writeVarint(value)
        }

        private fun ByteArrayOutputStream.writeBytesField(field: Int, value: ByteArray) {
            writeVarint(((field shl 3) or 2).toLong())
            writeVarint(value.size.toLong())
            write(value)
        }

        private fun ByteArrayOutputStream.writeStringField(field: Int, value: String) =
            writeBytesField(field, value.toByteArray(Charsets.UTF_8))
    }
}
