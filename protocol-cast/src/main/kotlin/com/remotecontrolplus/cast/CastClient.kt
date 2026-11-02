package com.remotecontrolplus.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class CastException(message: String) : Exception(message)

/**
 * A Cast v2 sender that only does what a remote needs: receiver volume and
 * mute, and play/pause/stop on whatever application is currently casting.
 *
 * Does not launch a receiver app of its own, so it works like the volume
 * buttons on the Google Home app rather than like a media player.
 */
class CastClient(
    private val host: String,
    private val port: Int = CAST_PORT,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    private var socket: SSLSocket? = null
    private var output: DataOutputStream? = null
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Transport id of the media application we have CONNECTed to, if any. */
    @Volatile
    private var mediaTransportId: String? = null

    private val _receiverStatus = MutableStateFlow<ReceiverStatus?>(null)
    val receiverStatus: StateFlow<ReceiverStatus?> = _receiverStatus.asStateFlow()

    private val _mediaStatus = MutableStateFlow<MediaStatus?>(null)
    val mediaStatus: StateFlow<MediaStatus?> = _mediaStatus.asStateFlow()

    private val _disconnects = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val disconnects: SharedFlow<String> = _disconnects.asSharedFlow()

    val isConnected: Boolean get() = socket?.let { it.isConnected && !it.isClosed } ?: false

    suspend fun connect(scope: CoroutineScope) {
        withContext(Dispatchers.IO) {
            val s = openTls()
            socket = s
            output = DataOutputStream(s.outputStream)
        }
        readerJob = scope.launch(Dispatchers.IO) { readLoop() }
        send(RECEIVER_ID, Namespaces.CONNECTION, buildJsonObject { put("type", "CONNECT") })
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HEARTBEAT_MS)
                send(RECEIVER_ID, Namespaces.HEARTBEAT, buildJsonObject { put("type", "PING") })
            }
        }
        refreshStatus()
    }

    fun close() {
        heartbeatJob?.cancel()
        readerJob?.cancel()
        writeScope.cancel()
        runCatching { socket?.close() }
        socket = null
        output = null
        mediaTransportId = null
    }

    // --------------------------------------------------------------- receiver

    suspend fun refreshStatus(): ReceiverStatus {
        val response = request(RECEIVER_ID, Namespaces.RECEIVER, buildJsonObject { put("type", "GET_STATUS") })
        return parseReceiverStatus(response).also { applyReceiverStatus(it) }
    }

    suspend fun setVolume(level: Double) {
        request(
            RECEIVER_ID,
            Namespaces.RECEIVER,
            buildJsonObject {
                put("type", "SET_VOLUME")
                putJsonObject("volume") { put("level", level.coerceIn(0.0, 1.0)) }
            },
        )
    }

    suspend fun setMuted(muted: Boolean) {
        request(
            RECEIVER_ID,
            Namespaces.RECEIVER,
            buildJsonObject {
                put("type", "SET_VOLUME")
                putJsonObject("volume") { put("muted", muted) }
            },
        )
    }

    /** Nudge the receiver volume by [delta] (0..1 scale). */
    suspend fun adjustVolume(delta: Double) {
        val current = _receiverStatus.value?.volumeLevel ?: refreshStatus().volumeLevel
        setVolume(current + delta)
    }

    suspend fun toggleMute() {
        val current = _receiverStatus.value?.muted ?: refreshStatus().muted
        setMuted(!current)
    }

    /** Stop the running application, which returns the device to its idle screen. */
    suspend fun stopApplication() {
        val app = _receiverStatus.value?.mediaApplication ?: return
        request(
            RECEIVER_ID,
            Namespaces.RECEIVER,
            buildJsonObject {
                put("type", "STOP")
                put("sessionId", app.sessionId)
            },
        )
    }

    // ------------------------------------------------------------------ media

    private suspend fun mediaTarget(): String {
        val app = _receiverStatus.value?.mediaApplication
            ?: refreshStatus().mediaApplication
            ?: throw CastException("Nothing is casting right now")
        if (mediaTransportId != app.transportId) {
            send(app.transportId, Namespaces.CONNECTION, buildJsonObject { put("type", "CONNECT") })
            mediaTransportId = app.transportId
        }
        return app.transportId
    }

    suspend fun refreshMediaStatus(): MediaStatus? {
        val target = mediaTarget()
        val response = request(target, Namespaces.MEDIA, buildJsonObject { put("type", "GET_STATUS") })
        return parseMediaStatus(response).also { _mediaStatus.value = it }
    }

    private suspend fun mediaCommand(type: String) {
        val target = mediaTarget()
        val session = _mediaStatus.value?.mediaSessionId
            ?: refreshMediaStatus()?.mediaSessionId
            ?: throw CastException("No media session to control")
        request(
            target,
            Namespaces.MEDIA,
            buildJsonObject {
                put("type", type)
                put("mediaSessionId", session)
            },
        )
    }

    suspend fun play() = mediaCommand("PLAY")
    suspend fun pause() = mediaCommand("PAUSE")

    suspend fun playPause() {
        val state = _mediaStatus.value?.playerState ?: refreshMediaStatus()?.playerState
        if (state == PlayerState.PLAYING) pause() else play()
    }

    // ------------------------------------------------------------- transport

    private fun openTls(): SSLSocket {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            // Cast devices present a self-signed certificate; the official
            // senders do not verify it either, and nothing sensitive crosses
            // this link.
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.startHandshake()
        return socket
    }

    private fun send(destination: String, namespace: String, payload: JsonObject) {
        val message = CastMessage(
            sourceId = SENDER_ID,
            destinationId = destination,
            namespace = namespace,
            payloadUtf8 = payload.toString(),
        )
        val bytes = message.encode()
        writeScope.launch {
            val out = output ?: return@launch
            runCatching {
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
            }.onFailure { fail(it.message ?: "write failed") }
        }
    }

    private suspend fun request(destination: String, namespace: String, payload: JsonObject): JsonObject {
        val id = requestId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val withId = JsonObject(payload + ("requestId" to kotlinx.serialization.json.JsonPrimitive(id)))
        send(destination, namespace, withId)
        return try {
            withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun readLoop() {
        val input = DataInputStream(socket?.inputStream ?: return)
        try {
            while (true) {
                val length = input.readInt()
                if (length <= 0 || length > MAX_FRAME) throw CastException("bad frame length $length")
                val buffer = ByteArray(length)
                input.readFully(buffer)
                handle(CastMessage.decode(buffer))
            }
        } catch (e: EOFException) {
            fail("the device closed the connection")
        } catch (e: Exception) {
            fail(e.message ?: "connection lost")
        }
    }

    private fun fail(reason: String) {
        pending.values.forEach { it.completeExceptionally(CastException(reason)) }
        pending.clear()
        _disconnects.tryEmit(reason)
    }

    private fun handle(message: CastMessage) {
        val text = message.payloadUtf8 ?: return
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val type = obj["type"]?.jsonPrimitive?.content

        when (message.namespace) {
            Namespaces.HEARTBEAT -> if (type == "PING") {
                send(message.sourceId, Namespaces.HEARTBEAT, buildJsonObject { put("type", "PONG") })
            }
            Namespaces.CONNECTION -> if (type == "CLOSE" && message.sourceId == mediaTransportId) {
                mediaTransportId = null
                _mediaStatus.value = null
            }
            Namespaces.RECEIVER -> if (type == "RECEIVER_STATUS") {
                runCatching { applyReceiverStatus(parseReceiverStatus(obj)) }
            }
            Namespaces.MEDIA -> if (type == "MEDIA_STATUS") {
                runCatching { _mediaStatus.value = parseMediaStatus(obj) }
            }
        }

        val id = obj["requestId"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
        pending.remove(id)?.complete(obj)
    }

    private fun applyReceiverStatus(status: ReceiverStatus) {
        _receiverStatus.value = status
        // The media app we were talking to has gone; forget its session.
        if (status.mediaApplication?.transportId != mediaTransportId) {
            mediaTransportId = null
            _mediaStatus.value = null
        }
    }

    private fun parseReceiverStatus(obj: JsonObject): ReceiverStatus {
        val status = obj["status"]?.jsonObject ?: throw CastException("RECEIVER_STATUS without status")
        val volume = status["volume"]?.jsonObject
        val apps = status["applications"]?.jsonArray?.map { el ->
            val app = el.jsonObject
            CastApplication(
                appId = app["appId"]?.jsonPrimitive?.content ?: "",
                displayName = app["displayName"]?.jsonPrimitive?.content ?: "",
                sessionId = app["sessionId"]?.jsonPrimitive?.content ?: "",
                transportId = app["transportId"]?.jsonPrimitive?.content ?: "",
                namespaces = app["namespaces"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                    ?: emptyList(),
                isIdleScreen = app["isIdleScreen"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        } ?: emptyList()
        return ReceiverStatus(
            volumeLevel = volume?.get("level")?.jsonPrimitive?.doubleOrNull ?: 0.0,
            muted = volume?.get("muted")?.jsonPrimitive?.booleanOrNull ?: false,
            applications = apps,
        )
    }

    private fun parseMediaStatus(obj: JsonObject): MediaStatus? {
        val status = obj["status"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val state = when (status["playerState"]?.jsonPrimitive?.content) {
            "PLAYING" -> PlayerState.PLAYING
            "PAUSED" -> PlayerState.PAUSED
            "BUFFERING" -> PlayerState.BUFFERING
            "IDLE" -> PlayerState.IDLE
            else -> PlayerState.UNKNOWN
        }
        val title = status["media"]?.jsonObject?.get("metadata")?.jsonObject
            ?.get("title")?.jsonPrimitive?.content
        return MediaStatus(
            mediaSessionId = status["mediaSessionId"]?.jsonPrimitive?.int ?: 0,
            playerState = state,
            title = title,
        )
    }

    private companion object {
        const val HEARTBEAT_MS = 5_000L
        const val REQUEST_TIMEOUT_MS = 8_000L
        const val CONNECT_TIMEOUT_MS = 5_000
        const val MAX_FRAME = 1 shl 20
    }
}
