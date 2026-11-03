package dev.amrakshay.atvremote.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import remote.Remotemessage.RemoteAppLinkLaunchRequest
import remote.Remotemessage.RemoteConfigure
import remote.Remotemessage.RemoteDeviceInfo
import remote.Remotemessage.RemoteDirection
import remote.Remotemessage.RemoteEditInfo
import remote.Remotemessage.RemoteImeBatchEdit
import remote.Remotemessage.RemoteImeObject
import remote.Remotemessage.RemoteKeyCode
import remote.Remotemessage.RemoteKeyInject
import remote.Remotemessage.RemoteMessage
import remote.Remotemessage.RemotePingResponse
import remote.Remotemessage.RemoteSetActive
import remote.Remotemessage.RemoteVoiceBegin
import remote.Remotemessage.RemoteVoiceEnd
import remote.Remotemessage.RemoteVoicePayload
import com.google.protobuf.ByteString
import kotlinx.coroutines.withTimeout
import java.io.OutputStream
import javax.net.ssl.SSLSocket

data class VolumeInfo(val level: Int, val max: Int, val muted: Boolean) {
    val fraction: Float get() = if (max > 0) level.toFloat() / max else 0f
}

data class DeviceInfo(val vendor: String, val model: String, val version: String)

class NotPaired(message: String) : Exception(message)
class ConnectionLost(message: String) : Exception(message)

/**
 * Port 6466. The live session.
 *
 * Features we advertise: PING | KEY | IME | POWER | VOLUME | APP_LINK.
 * IME is what makes [currentApp] report anything.
 */
private const val ACTIVE_FEATURES = 1 or 2 or 4 or 32 or 64 or 512 // 615

/** Voice feature bit (Remote Control Plus addition, after androidtvremote2). */
private const val FEATURE_VOICE = 8

/** 16-bit mono PCM at 8 kHz, in 8 KB to 20 KB payloads (androidtvremote2). */
const val VOICE_SAMPLE_RATE = 8000
const val VOICE_CHUNK_MIN = 8 * 1024
const val VOICE_CHUNK_MAX = 20 * 1024

class VoiceUnavailable(message: String) : Exception(message)

class RemoteSession(
    private val host: String,
    private val identity: ClientIdentity,
) : Remote {

    override val capabilities = Capabilities(
        kind = TransportKind.Native,
        // Volume, the running app and the focused field all arrive unasked.
        pushesState = true,
        announcesTextFields = true,
        replacesFieldText = true,
        // The protocol launches by deep link and has no way to ask what is installed.
        listsApps = false,
    )

    override val apps: StateFlow<List<RemoteApp>> = MutableStateFlow(emptyList<RemoteApp>()).asStateFlow()

    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private var readerJob: Job? = null
    private val frames = FrameReader()
    private val started = CompletableDeferred<Unit>()

    /** Features both sides support, settled on the TV's RemoteConfigure. */
    @Volatile private var activeFeatures: Int = ACTIVE_FEATURES or FEATURE_VOICE

    /** True when the TV agreed to voice in the feature negotiation. */
    val voiceSupported: Boolean get() = activeFeatures and FEATURE_VOICE != 0

    @Volatile private var voiceBegin: CompletableDeferred<Int>? = null

    /**
     * Writes go here, never on the caller's thread.
     *
     * [sendKey] and friends are called straight from UI callbacks, and a socket
     * write on Android's main thread throws NetworkOnMainThreadException — which
     * cost an afternoon, because the UI kept receiving volume updates (the reader
     * has its own thread) while every key press vanished.
     *
     * limitedParallelism(1) keeps writes ordered, so a key press cannot interleave
     * with the reader thread's ping reply and corrupt the stream.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _volume = MutableStateFlow<VolumeInfo?>(null)
    override val volume: StateFlow<VolumeInfo?> = _volume.asStateFlow()

    private val _isOn = MutableStateFlow(false)
    val isOn: StateFlow<Boolean> = _isOn.asStateFlow()

    private val _currentApp = MutableStateFlow("")
    override val currentApp: StateFlow<String> = _currentApp.asStateFlow()

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    override val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    private val _textField = MutableStateFlow("")
    /** What the TV's focused text field currently holds, as the TV reports it. */
    override val textField: StateFlow<String> = _textField.asStateFlow()

    private val _imeReady = MutableStateFlow(false)
    /**
     * True once the TV has sent a RemoteImeBatchEdit on *this* session, which is
     * the only way to learn the counters it expects echoed back. Typing before
     * this is silently ignored by the TV, so the UI must wait for it.
     */
    override val imeReady: StateFlow<Boolean> = _imeReady.asStateFlow()

    // The TV hands these out in every RemoteImeBatchEdit and expects them echoed
    // back on anything we send. Starting from zero works until the first one
    // arrives, which is why the keyboard is only offered once a field is focused.
    @Volatile private var imeCounter: Int = 0
    @Volatile private var fieldCounter: Int = 0

    private val _disconnects = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Emits when the connection drops; the UI reconnects rather than erroring out. */
    override val disconnects: SharedFlow<String> = _disconnects.asSharedFlow()

    /** Connects and returns once the TV has sent RemoteStart. */
    override suspend fun connect(scope: CoroutineScope) {
        withContext(Dispatchers.IO) {
            val s = connectTls(host, REMOTE_PORT, identity)
            socket = s
            output = s.outputStream
        }
        readerJob = scope.launch(Dispatchers.IO) { readLoop() }
        started.await()
    }

    override fun sendKey(key: RemoteKeyCode, direction: RemoteDirection) {
        send(
            RemoteMessage.newBuilder().setRemoteKeyInject(
                RemoteKeyInject.newBuilder().setKeyCode(key).setDirection(direction),
            ),
        )
    }

    /** Press and hold, for repeat-on-hold. Pair with [releaseKey]. */
    fun holdKey(key: RemoteKeyCode) = sendKey(key, RemoteDirection.START_LONG)

    fun releaseKey(key: RemoteKeyCode) = sendKey(key, RemoteDirection.END_LONG)

    /**
     * Types [text] into whatever field the TV has focused — the point of the
     * whole app, since pecking at an on-screen grid with the D-pad is miserable.
     *
     * `start` and `end` are both the text length minus one, which is what the
     * reference implementation sends and what the TV accepts.
     */
    override fun sendText(text: String) {
        if (text.isEmpty()) return
        val position = text.length - 1
        send(
            RemoteMessage.newBuilder().setRemoteImeBatchEdit(
                RemoteImeBatchEdit.newBuilder()
                    .setImeCounter(imeCounter)
                    .setFieldCounter(fieldCounter)
                    .addEditInfo(
                        RemoteEditInfo.newBuilder()
                            .setInsert(1)
                            .setTextFieldStatus(
                                RemoteImeObject.newBuilder()
                                    .setStart(position)
                                    .setEnd(position)
                                    .setValue(text),
                            ),
                    ),
            ),
        )
    }

    override fun launchApp(target: String) {
        send(
            RemoteMessage.newBuilder().setRemoteAppLinkLaunchRequest(
                RemoteAppLinkLaunchRequest.newBuilder().setAppLink(target),
            ),
        )
    }

    /**
     * Open a voice session: press search, wait for the TV to offer a session
     * id, and echo it back. Audio may be sent once this returns.
     * Remote Control Plus addition, following androidtvremote2.
     */
    suspend fun beginVoice(timeoutMs: Long = 3000): Int {
        val deferred = CompletableDeferred<Int>()
        voiceBegin = deferred
        try {
            sendKey(RemoteKeyCode.KEYCODE_SEARCH)
            val session = try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw VoiceUnavailable(
                    if (voiceSupported) "The TV did not start a voice session"
                    else "This TV does not accept voice from a remote app",
                )
            }
            send(
                RemoteMessage.newBuilder().setRemoteVoiceBegin(
                    RemoteVoiceBegin.newBuilder().setSessionId(session),
                ),
            )
            return session
        } finally {
            voiceBegin = null
        }
    }

    /** Send PCM, split to at most 20 KB per message and padded to at least 8 KB. */
    fun sendVoice(session: Int, pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + VOICE_CHUNK_MAX, pcm.size)
            var piece = pcm.copyOfRange(offset, end)
            if (piece.size < VOICE_CHUNK_MIN) piece = piece.copyOf(VOICE_CHUNK_MIN)
            send(
                RemoteMessage.newBuilder().setRemoteVoicePayload(
                    RemoteVoicePayload.newBuilder()
                        .setSessionId(session)
                        .setSamples(ByteString.copyFrom(piece)),
                ),
            )
            offset = end
        }
    }

    fun endVoice(session: Int) {
        send(
            RemoteMessage.newBuilder().setRemoteVoiceEnd(
                RemoteVoiceEnd.newBuilder().setSessionId(session),
            ),
        )
    }

    override fun close() {
        readerJob?.cancel()
        writeScope.cancel()
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    private fun send(builder: RemoteMessage.Builder) {
        val bytes = FrameReader.frame(builder.build().toByteArray())
        writeScope.launch {
            val out = output ?: return@launch
            runCatching {
                out.write(bytes)
                out.flush()
            }.onFailure { fail(it.message ?: "write failed") }
        }
    }

    private fun readLoop() {
        val stream = socket?.inputStream ?: return
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                for (raw in frames.add(buffer, read)) handle(RemoteMessage.parseFrom(raw))
            }
            fail("the TV closed the connection")
        } catch (e: Exception) {
            fail(e.message ?: "connection lost")
        }
    }

    private fun fail(reason: String) {
        if (!started.isCompleted) started.completeExceptionally(ConnectionLost(reason))
        _disconnects.tryEmit(reason)
    }

    private fun handle(message: RemoteMessage) {
        when {
            message.hasRemoteConfigure() -> {
                val offered = message.remoteConfigure.code1
                activeFeatures = (ACTIVE_FEATURES or FEATURE_VOICE) and offered
                val info = message.remoteConfigure.deviceInfo
                _device.value = DeviceInfo(info.vendor, info.model, info.appVersion)
                send(
                    RemoteMessage.newBuilder().setRemoteConfigure(
                        RemoteConfigure.newBuilder()
                            .setCode1(activeFeatures)
                            .setDeviceInfo(
                                RemoteDeviceInfo.newBuilder()
                                    .setUnknown1(1)
                                    .setUnknown2("1")
                                    .setPackageName("atvremote")
                                    .setAppVersion("1.0.0"),
                            ),
                    ),
                )
            }

            message.hasRemoteSetActive() -> send(
                RemoteMessage.newBuilder().setRemoteSetActive(
                    RemoteSetActive.newBuilder().setActive(activeFeatures),
                ),
            )

            message.hasRemoteStart() -> {
                _isOn.value = message.remoteStart.started
                if (!started.isCompleted) started.complete(Unit)
            }

            // Keepalive. Miss these and the TV hangs up.
            message.hasRemotePingRequest() -> send(
                RemoteMessage.newBuilder().setRemotePingResponse(
                    RemotePingResponse.newBuilder().setVal1(message.remotePingRequest.val1),
                ),
            )

            // Pushed by the TV for ANY volume change, including the physical remote.
            message.hasRemoteSetVolumeLevel() -> {
                val v = message.remoteSetVolumeLevel
                _volume.value = VolumeInfo(v.volumeLevel, v.volumeMax, v.volumeMuted)
            }

            message.hasRemoteImeKeyInject() ->
                _currentApp.value = message.remoteImeKeyInject.appInfo.appPackage

            message.hasRemoteImeBatchEdit() -> {
                val edit = message.remoteImeBatchEdit
                imeCounter = edit.imeCounter
                fieldCounter = edit.fieldCounter
                _imeReady.value = true
                edit.editInfoList.firstOrNull()
                    ?.takeIf { it.hasTextFieldStatus() }
                    ?.let { _textField.value = it.textFieldStatus.value }
            }

            // The TV answers KEYCODE_SEARCH with a session id once voice is on.
            message.hasRemoteVoiceBegin() -> voiceBegin?.complete(message.remoteVoiceBegin.sessionId)

            message.hasRemoteError() -> _disconnects.tryEmit("TV reported an error")
        }
    }
}
