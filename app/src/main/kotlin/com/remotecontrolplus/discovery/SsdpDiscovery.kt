package com.remotecontrolplus.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.remotecontrolplus.core.DeviceType
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
 * Sends an M-SEARCH per search target and reads replies until [timeoutMs].
 */
class SsdpDiscovery(context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private data class Reply(val host: String, val st: String, val location: String?)

    suspend fun scan(timeoutMs: Long, onFound: (TvDevice) -> Unit) = withContext(Dispatchers.IO) {
        val lock = wifi.createMulticastLock("remotecontrolplus-ssdp").apply { setReferenceCounted(false) }
        val replies = LinkedHashMap<String, Reply>()
        try {
            lock.acquire()
            DatagramSocket().use { socket ->
                socket.soTimeout = 1000
                val group = InetAddress.getByName(SSDP_ADDRESS)
                for (st in listOf(ST_SAMSUNG, ST_LG, ST_ROKU)) {
                    val request = "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_ADDRESS:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val bytes = request.toByteArray()
                    repeat(2) { socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT)) }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    val headers = text.lines().drop(1).mapNotNull { line ->
                        val i = line.indexOf(':')
                        if (i <= 0) null else line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim()
                    }.toMap()
                    val st = headers["ST"] ?: continue
                    val host = packet.address.hostAddress ?: continue
                    replies.putIfAbsent("$host|$st", Reply(host, st, headers["LOCATION"]))
                }
            }
        } finally {
            runCatching { lock.release() }
        }

        replies.values.distinctBy { it.host + it.st }.forEach { reply ->
            runCatching { describe(reply) }.getOrNull()?.let(onFound)
        }
    }

    private fun describe(reply: Reply): TvDevice? = when (reply.st) {
        ST_SAMSUNG -> samsung(reply.host)
        ST_LG -> upnpDevice(reply, DeviceType.LG)
        ST_ROKU -> roku(reply.host)
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

    /** Standard UPnP device description XML, used for LG. */
    private fun upnpDevice(reply: Reply, type: DeviceType): TvDevice? {
        val location = reply.location ?: return null
        val body = get(location) ?: return null
        val host = URL(location).host
        val name = tag(body, "friendlyName") ?: type.label
        val udn = tag(body, "UDN") ?: host
        return TvDevice(
            id = "${type.name.lowercase()}-$udn",
            name = name,
            type = type,
            host = host,
            port = 3001,
            model = tag(body, "modelName"),
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
    }
}
