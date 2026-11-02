package com.remotecontrolplus.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.remotecontrolplus.core.DeviceType
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.core.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * SSDP (UPnP) discovery for Samsung, LG and Roku, which do not use mDNS.
 *
 * Sends an M-SEARCH for each vendor's search target and one for `ssdp:all`,
 * then reads replies until [scan]'s timeout. Replies are classified by their
 * ST/USN/SERVER headers, so a TV that only answers the catch-all is still
 * recognised.
 */
class SsdpDiscovery(context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private data class Reply(val host: String, val headers: Map<String, String>) {
        val st get() = headers["ST"] ?: ""
        val usn get() = headers["USN"] ?: ""
        val server get() = headers["SERVER"] ?: ""
        val location get() = headers["LOCATION"]
    }

    suspend fun scan(timeoutMs: Long, onFound: (TvDevice) -> Unit) = withContext(Dispatchers.IO) {
        val lock = wifi.createMulticastLock("remotecontrolplus-ssdp").apply { setReferenceCounted(false) }
        val replies = LinkedHashMap<String, Reply>()
        var packets = 0
        try {
            lock.acquire()
            DatagramSocket().use { socket ->
                socket.soTimeout = 1000
                val group = InetAddress.getByName(SSDP_ADDRESS)
                for (st in listOf(ST_SAMSUNG, ST_LG, ST_ROKU, ST_ALL)) {
                    val request = "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_ADDRESS:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val bytes = request.toByteArray()
                    repeat(2) { socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT)) }
                }
                Diagnostics.log("ssdp", "M-SEARCH sent for Samsung, LG, Roku and ssdp:all")
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    packets++
                    val text = String(packet.data, 0, packet.length)
                    val headers = text.lines().drop(1).mapNotNull { line ->
                        val i = line.indexOf(':')
                        if (i <= 0) null else line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim()
                    }.toMap()
                    val host = packet.address.hostAddress ?: continue
                    val reply = Reply(host, headers)
                    if (replies.putIfAbsent("$host|${reply.st}", reply) == null) {
                        Diagnostics.log("ssdp", "$host ST=${reply.st} SERVER=${reply.server} USN=${reply.usn.take(60)}")
                    }
                }
            }
        } catch (e: Exception) {
            Diagnostics.log("ssdp", "scan failed", e)
        } finally {
            runCatching { lock.release() }
        }
        Diagnostics.log("ssdp", "$packets packets, ${replies.size} distinct replies")

        val seen = HashSet<String>()
        replies.values.forEach { reply ->
            val type = classify(reply) ?: return@forEach
            if (!seen.add("${reply.host}|$type")) return@forEach
            val device = runCatching { describe(reply.host, type, reply.location) }
                .onFailure { Diagnostics.log("ssdp", "${reply.host} looked like ${type.label} but description failed", it) }
                .getOrNull()
            if (device != null) {
                Diagnostics.log("ssdp", "found ${device.type.label} '${device.name}' at ${device.host}")
                onFound(device)
            }
        }
    }

    private fun classify(reply: Reply): DeviceType? {
        val st = reply.st.lowercase()
        val usn = reply.usn.lowercase()
        val server = reply.server.lowercase()
        return when {
            st == ST_SAMSUNG.lowercase() || "samsung" in server || "samsung" in usn -> DeviceType.SAMSUNG
            st == ST_LG.lowercase() || "webos" in server || "lge" in st || "lge" in usn -> DeviceType.LG
            st == ST_ROKU.lowercase() || "roku" in server || "roku" in usn -> DeviceType.ROKU
            else -> null
        }
    }

    /** Build a device record for a host of known type, by asking it to describe itself. */
    fun describe(host: String, type: DeviceType, location: String? = null): TvDevice? = when (type) {
        DeviceType.SAMSUNG -> samsung(host)
        DeviceType.LG -> lg(host, location)
        DeviceType.ROKU -> roku(host)
        else -> null
    }

    /** Samsung exposes a JSON description with the name, model and Wi-Fi MAC. */
    private fun samsung(host: String): TvDevice? {
        val body = get("http://$host:8001/api/v2/") ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val device = root["device"]?.jsonObject ?: return null
        val name = device["name"]?.jsonPrimitive?.content ?: "Samsung TV"
        val id = device["id"]?.jsonPrimitive?.content ?: host
        return TvDevice(
            id = "samsung-$id",
            name = name,
            type = DeviceType.SAMSUNG,
            host = host,
            port = 8002,
            model = device["modelName"]?.jsonPrimitive?.content,
            mac = device["wifiMac"]?.jsonPrimitive?.content,
        )
    }

    private fun roku(host: String): TvDevice? {
        val body = get("http://$host:8060/query/device-info") ?: return null
        val name = tag(body, "user-device-name") ?: tag(body, "friendly-device-name") ?: "Roku"
        val id = tag(body, "serial-number") ?: host
        return TvDevice(
            id = "roku-$id",
            name = name,
            type = DeviceType.ROKU,
            host = host,
            port = 8060,
            model = tag(body, "model-name"),
            mac = tag(body, "wifi-mac") ?: tag(body, "ethernet-mac"),
        )
    }

    /** LG: the UPnP device description when SSDP gave us one, else a bare record. */
    private fun lg(host: String, location: String?): TvDevice {
        val body = location?.let { runCatching { get(it) }.getOrNull() }
        val name = body?.let { tag(it, "friendlyName") } ?: "LG TV"
        val udn = body?.let { tag(it, "UDN") } ?: host
        return TvDevice(
            id = "lg-$udn",
            name = name,
            type = DeviceType.LG,
            host = host,
            port = 3001,
            model = body?.let { tag(it, "modelName") },
        )
    }

    private fun get(url: String): String? =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val ST_SAMSUNG = "urn:samsung.com:device:RemoteControlReceiver:1"
        const val ST_LG = "urn:lge-com:service:webos-second-screen:1"
        const val ST_ROKU = "roku:ecp"
        const val ST_ALL = "ssdp:all"
    }
}
