package dev.atvremote.protocol

import dev.atvremote.protocol.opack.Opack
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The _siA content uses integer keys, as in the pyatv capture. */
class SiriAudioOpackTest {
    @Test
    fun siriAudioContentRoundTrips() {
        val frames = listOf(ByteArray(41) { 0x48 }, ByteArray(40) { 0x48 })
        val content = mapOf(
            3 to frames[0] + frames[1],
            4 to 0.08,
            5 to listOf(mapOf(1 to 41, 2 to 0), mapOf(1 to 40, 2 to 41)),
        )
        val decoded = Opack.unpack(Opack.pack(mapOf("_i" to "_siA", "_t" to 1, "_c" to content))) as Map<*, *>
        val c = decoded["_c"] as Map<*, *>
        assertContentEquals(frames[0] + frames[1], c[3L] as ByteArray)
        assertEquals(0.08, c[4L])
        assertEquals(listOf(mapOf(1L to 41L, 2L to 0L), mapOf(1L to 40L, 2L to 41L)), c[5L])
    }
}
