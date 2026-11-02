package com.remotecontrolplus.cast

import kotlin.test.Test
import kotlin.test.assertEquals

class CastMessageTest {

    @Test
    fun `string payload round-trips`() {
        val message = CastMessage(
            sourceId = "sender-0",
            destinationId = "receiver-0",
            namespace = Namespaces.CONNECTION,
            payloadUtf8 = """{"type":"CONNECT"}""",
        )
        assertEquals(message, CastMessage.decode(message.encode()))
    }

    @Test
    fun `binary payload round-trips`() {
        val message = CastMessage(
            sourceId = "sender-0",
            destinationId = "receiver-0",
            namespace = "urn:x-cast:test",
            payloadBinary = byteArrayOf(0, 1, 2, 0x7F, -1),
        )
        assertEquals(message, CastMessage.decode(message.encode()))
    }

    @Test
    fun `encoding matches a known frame`() {
        // Field tags and lengths as the reference node-castv2 sender emits for CONNECT.
        val expected = "0800" +
            "1208" + "sender-0".hex() +
            "1a0a" + "receiver-0".hex() +
            "2228" + Namespaces.CONNECTION.hex() +
            "2800" +
            "3212" + """{"type":"CONNECT"}""".hex()
        val message = CastMessage(
            sourceId = "sender-0",
            destinationId = "receiver-0",
            namespace = Namespaces.CONNECTION,
            payloadUtf8 = """{"type":"CONNECT"}""",
        )
        assertEquals(expected, message.encode().joinToString("") { "%02x".format(it) })
    }

    private fun String.hex() = toByteArray().joinToString("") { "%02x".format(it) }
}
