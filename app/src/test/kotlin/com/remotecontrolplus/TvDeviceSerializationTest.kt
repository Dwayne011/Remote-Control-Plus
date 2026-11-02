package com.remotecontrolplus

import com.remotecontrolplus.core.DeviceType
import com.remotecontrolplus.core.TvDevice
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TvDeviceSerializationTest {

    @Test
    fun `saved devices round-trip through JSON`() {
        val devices = listOf(
            TvDevice("samsung-1", "Living room", DeviceType.SAMSUNG, "192.168.1.10", 8002, "UN55", "aa:bb:cc:dd:ee:ff"),
            TvDevice("atv-id", "Bedroom", DeviceType.APPLE_TV, "192.168.1.11", 49152, "AppleTV14,1"),
            TvDevice("cast-id", "Kitchen", DeviceType.CHROMECAST, "192.168.1.12", 8009),
        )
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(ListSerializer(TvDevice.serializer()), devices)
        assertEquals(devices, json.decodeFromString(ListSerializer(TvDevice.serializer()), encoded))
    }

    @Test
    fun `unknown fields from a newer version are ignored`() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(
            TvDevice.serializer(),
            """{"id":"roku-1","name":"Roku","type":"ROKU","host":"10.0.0.5","port":8060,"future":"x"}""",
        )
        assertEquals(DeviceType.ROKU, decoded.type)
    }
}
