package com.remotecontrolplus.devices

import com.remotecontrolplus.core.Capabilities
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.core.NotConnectedException
import com.remotecontrolplus.core.Pairable
import com.remotecontrolplus.core.PairingException
import com.remotecontrolplus.core.PairingHandle
import com.remotecontrolplus.core.RemoteKey
import com.remotecontrolplus.core.TextFieldState
import com.remotecontrolplus.core.TvController
import com.remotecontrolplus.core.TvDevice
import com.remotecontrolplus.core.HoldableAssistant
import com.remotecontrolplus.core.OpusEncoder
import com.remotecontrolplus.core.VoiceCapable
import com.remotecontrolplus.core.VoicePairable
import com.remotecontrolplus.core.VoicePairingRequired
import dev.atvremote.protocol.airplay.AirPlayAuth
import dev.atvremote.protocol.airplay.AirPlayConnection
import dev.atvremote.protocol.airplay.Ap2Session
import dev.atvremote.protocol.mrp.MrpVoice
import dev.atvremote.protocol.companion.AppleTvRemote
import dev.atvremote.protocol.companion.Button
import dev.atvremote.protocol.companion.CompanionClient
import dev.atvremote.protocol.hap.Credentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Apple TV over Companion Link, via the vendored protocol module.
 *
 * The Companion port is ephemeral: the Apple TV changes it across reboots,
 * so [portResolver] re-discovers it over mDNS before each connection and
 * the stored port is only a fallback.
 */
class AppleTvController(
    override val device: TvDevice,
    private val serializedCredentials: String?,
    private val airplayCredentials: String? = null,
    private val scope: CoroutineScope,
    private val portResolver: suspend (TvDevice, Long) -> Int?,
    private val cachedPort: (TvDevice) -> Int?,
    private val rememberPort: (TvDevice, Int) -> Unit,
) : TvController, Pairable, HoldableAssistant, VoiceCapable, VoicePairable {

    override val capabilities = Capabilities(
        // Apple TV has no mute key; volume routes over HDMI-CEC when the TV supports it.
        // No mute key over Companion; mute is emulated through the volume level.
        keys = Capabilities.ALL_KEYS,
        textEntry = true,
        volumeSlider = true,
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _textField = MutableStateFlow(TextFieldState(focused = false, text = ""))
    override val textField: StateFlow<TextFieldState> = _textField.asStateFlow()

    private var remote: AppleTvRemote? = null
    @Volatile private var volumeSupported = false
    private val _powerState = MutableStateFlow<Boolean?>(null)
    override val powerState: StateFlow<Boolean?> = _powerState.asStateFlow()
    private val _volume = MutableStateFlow<Float?>(null)
    override val volume: StateFlow<Float?> = _volume.asStateFlow()

    override suspend fun connect() {
        val credentials = serializedCredentials?.let { runCatching { Credentials.parse(it) }.getOrNull() }
        if (credentials == null) {
            _state.value = ConnectionState.PairingRequired
            return
        }
        _state.value = ConnectionState.Connecting
        // A sleeping Apple TV takes several seconds to wake when contacted
        // over the network, and its Companion port moves across reboots. So:
        // look the port up fresh each attempt (falling back to the last port
        // that worked), and keep trying for about 30 seconds before giving up.
        val lookupTimeouts = listOf(4_000L, 6_000L, 6_000L, 8_000L)
        var lastError: Exception? = null
        for ((index, lookupTimeout) in lookupTimeouts.withIndex()) {
            if (index > 0) {
                Diagnostics.log("appletv", "attempt ${index + 1} of ${lookupTimeouts.size}; the Apple TV may be waking up")
                delay(1_500)
            }
            val port = portResolver(device, lookupTimeout) ?: cachedPort(device) ?: device.port
            try {
                attempt(credentials, port)
                return
            } catch (e: dev.atvremote.protocol.companion.PairingException) {
                Diagnostics.log("appletv", "credentials rejected; pairing required", e)
                _state.value = ConnectionState.PairingRequired
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.log("appletv", "connect to port $port failed", e)
                remote?.close()
                remote = null
                lastError = e
            }
        }
        _state.value = ConnectionState.Error(
            if (lastError is java.net.SocketTimeoutException) "Apple TV not reachable. Is it on the same Wi-Fi?"
            else lastError?.message ?: "Could not connect",
        )
    }


    private fun powerFromStatus(state: Int?): Boolean? = when (state) {
        1 -> false
        2, 3, 4 -> true
        else -> null
    }
    private suspend fun attempt(credentials: Credentials, port: Int) {
        Diagnostics.log("appletv", "connecting to ${device.host}:$port with stored credentials")
        val r = AppleTvRemote(device.host, port, credentials, scope)
        r.onDisconnect = { cause ->
            if (_state.value is ConnectionState.Connected) {
                _state.value = ConnectionState.Error(cause?.message ?: "Connection lost")
            }
        }
        r.onCapabilities = { caps ->
            volumeSupported = caps.volume
            Diagnostics.log("appletv", "device capabilities: volume=${caps.volume} play=${caps.play}")
            if (!caps.volume) _volume.value = null else scope.launch { refreshVolume() }
        }
        // TVSystemStatus: 1 asleep, 2 screensaver, 3 awake, 4 idle (pyatv SystemStatus).
        r.onEvent = { name, content ->
            if (name == "TVSystemStatus") {
                val s = (content["state"] as? Number)?.toInt()
                Diagnostics.log("appletv", "system status $s")
                powerFromStatus(s)?.let { _powerState.value = it }
            }
        }
        r.onTextFocus = { session ->
            _textField.value = TextFieldState(
                focused = session != null,
                text = session?.textBeforeCursor ?: "",
            )
        }
        remote = r
        r.connect()
        rememberPort(device, port)
        // A sleeping Apple TV still accepts connections, so ask rather than assume.
        r.subscribe("TVSystemStatus")
        val attention = runCatching { r.fetchAttentionState() }
            .onFailure { Diagnostics.log("appletv", "attention state request failed", it) }
            .getOrNull()
        _powerState.value = powerFromStatus(attention)
        Diagnostics.log("appletv", "attention state on connect: $attention -> on=${_powerState.value}")
        Diagnostics.log("appletv", "connected and session started")
        _state.value = ConnectionState.Connected
    }

    override fun disconnect() {
        ap2?.close()
        ap2 = null
        voiceDeviceId = null
        _powerState.value = null
        remote?.close()
        remote = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun sendKey(key: RemoteKey) {
        val r = remote ?: throw NotConnectedException()
        when (key) {
            RemoteKey.POWER_ON -> { r.wake(); _powerState.value = true }
            RemoteKey.POWER_OFF -> { r.sleep(); _powerState.value = false }
            RemoteKey.VOLUME_UP -> { r.volumeUp(); refreshVolume() }
            RemoteKey.VOLUME_DOWN -> { r.volumeDown(); refreshVolume() }
            RemoteKey.MUTE -> toggleMute(r)
            RemoteKey.UP -> r.up()
            RemoteKey.DOWN -> r.down()
            RemoteKey.LEFT -> r.left()
            RemoteKey.RIGHT -> r.right()
            RemoteKey.SELECT -> r.select()
            RemoteKey.BACK -> r.menu()
            RemoteKey.HOME -> r.home()
            RemoteKey.PLAY -> r.play()
            RemoteKey.PAUSE -> r.pause()
            RemoteKey.PREVIOUS -> r.previousTrack()
            RemoteKey.NEXT -> r.nextTrack()
            RemoteKey.ASSISTANT -> r.press(Button.SIRI, holdMs = 300)
        }
    }


    /** Volume before muting, restored on unmute. */
    private var mutedFrom: Double? = null

    /**
     * Apple TV has no mute command; set the volume to zero and restore it.
     * Only possible when the Apple TV routes volume (HDMI-CEC or eARC).
     */
    private suspend fun toggleMute(r: AppleTvRemote) {
        val restore = mutedFrom
        if (restore != null) {
            r.setVolume(restore)
            mutedFrom = null
            Diagnostics.log("appletv", "unmuted to $restore")
            return
        }
        val current = runCatching { r.getVolume() }.getOrNull()
            ?: throw IllegalStateException("This Apple TV is not controlling the TV's volume, so it cannot mute")
        r.setVolume(0.0)
        mutedFrom = if (current > 0.0) current else 0.3
        Diagnostics.log("appletv", "muted from $current")
    }
    /** A swipe on the pad becomes a swipe on the Siri Remote's virtual trackpad. */
    override suspend fun swipe(direction: RemoteKey) {
        val r = remote ?: throw NotConnectedException()
        val (from, to) = when (direction) {
            RemoteKey.UP -> (500 to 700) to (500 to 300)
            RemoteKey.DOWN -> (500 to 300) to (500 to 700)
            RemoteKey.LEFT -> (700 to 500) to (300 to 500)
            RemoteKey.RIGHT -> (300 to 500) to (700 to 500)
            else -> return sendKey(direction)
        }
        r.swipe(from.first, from.second, to.first, to.second, durationMs = 120)
    }

    override suspend fun setVolume(fraction: Float) {
        val r = remote ?: throw NotConnectedException()
        r.setVolume(fraction.toDouble())
        refreshVolume()
    }

    private suspend fun refreshVolume() {
        val r = remote ?: return
        if (!volumeSupported) return
        _volume.value = runCatching { r.getVolume()?.toFloat() }.getOrNull()
    }



    // ------------------------------------------------- Siri voice (experimental)
    // Siri audio over the MRP channel tunnelled through AirPlay: register as a
    // voice input device, hold Siri, mark recording, stream Opus packets. The
    // messages come from pyatv's protobuf definitions; no public client is known
    // to have used them on current tvOS, so every step is logged.

    override val voiceSampleRate = MrpVoice.SAMPLE_RATE
    override val voiceChunkBytes = MrpVoice.FRAME_SAMPLES * 2

    private var ap2: Ap2Session? = null
    private var voiceDeviceId: Int? = null
    private var encoder: OpusEncoder? = null
    private var sampleTime = 0L
    private var packetsSent = 0

    private val sessionLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Flow confirmed on tvOS 27: press Siri over MRP, the TV answers
     * SetRecordingState(Recording), audio is accepted, release, the TV answers
     * NotRecording. Siri still showed nothing, so the audio format is the open
     * question: holds alternate between plain 16-bit PCM and Opus, and the
     * voice device is re-registered when the format changes.
     */
    private var holdCount = 0
    private var format = MrpVoice.Format.OPUS

    /** Packing experiments, one per hold (Opus, as Apple's capture registers). */
    private enum class Variant(val label: String, val packing: MrpVoice.Packing, val batch: Int) {
        FIXED("fixed-length packets, 1 per message", MrpVoice.Packing(variableFrames = 0), 1),
        BATCH5("fixed-length packets, 5 per message", MrpVoice.Packing(variableFrames = 0), 5),
        CLOCK("fixed-length, clock-time timestamps", MrpVoice.Packing(variableFrames = 0, clockTimestamp = true), 1),
        NO_FORMAT("fixed-length, no format inside each message", MrpVoice.Packing(variableFrames = 0, formatInBuffer = false), 1),
    }
    private var variant = Variant.FIXED
    private var registeredFormat: MrpVoice.Format? = null

    /** True once the TV has said Recording during this hold. */
    @Volatile private var tvRecording = false
    @Volatile private var tvSaidStop = false
    private val pending = java.util.ArrayDeque<ByteArray>()
    private var unsentTail: List<ByteArray> = emptyList()

    private suspend fun ensureSession(creds: Credentials): Ap2Session = sessionLock.withLock {
        ap2?.takeIf { it.isConnected }?.let { return@withLock it }
        Diagnostics.log("siri-voice", "opening AirPlay session to ${device.host}:7000")
        val session = Ap2Session(device.host, creds, scope, "Remote Control Plus")
        session.onDisconnect = {
            Diagnostics.log("siri-voice", "AirPlay session closed: ${it?.message}")
            ap2 = null
            voiceDeviceId = null
            registeredFormat = null
        }
        session.onRecordingState = { state, error ->
            Diagnostics.log("siri-voice", "TV recording state: ${state ?: "none"}${error?.let { " error=$it" } ?: ""} (${packetsSent} blocks sent so far)")
            when (state) {
                MrpVoice.RECORDING -> tvRecording = true
                MrpVoice.NOT_RECORDING -> { tvRecording = false; tvSaidStop = true }
            }
        }
        session.connect()
        ap2 = session
        Diagnostics.log("siri-voice", "AirPlay session up; MRP tunnel ready")
        session
    }

    private fun sendSiriKey(session: Ap2Session, down: Boolean) {
        session.sendMrp(MrpVoice.hidEvent(MrpVoice.SIRI_USAGE_PAGE, MrpVoice.SIRI_USAGE, down))
        session.sendMrp(MrpVoice.generic())
    }

    override suspend fun beginVoice(): Int {
        if (remote == null) throw NotConnectedException()
        val creds = airplayCredentials?.let { runCatching { Credentials.parse(it) }.getOrNull() }
            ?: throw VoicePairingRequired()
        val session = ensureSession(creds)
        variant = Variant.entries[holdCount % Variant.entries.size]
        format = MrpVoice.Format.OPUS
        holdCount++
        if (registeredFormat != format || voiceDeviceId == null) {
            voiceDeviceId = session.registerVoiceInput(format)
            registeredFormat = format
            Diagnostics.log("siri-voice", "registered voice input device $voiceDeviceId for $format")
        }
        tvRecording = false
        tvSaidStop = false
        synchronized(pending) { pending.clear() }
        unsentTail = emptyList()
        encoder = if (format == MrpVoice.Format.OPUS) OpusEncoder(MrpVoice.SAMPLE_RATE) else null
        sampleTime = 0L
        packetsSent = 0
        Diagnostics.log("siri-voice", "hold #$holdCount: ${variant.label}; Siri key down over MRP, waiting for the TV")
        sendSiriKey(session, down = true)
        return voiceDeviceId ?: 0
    }

    override suspend fun sendVoice(session: Int, pcm: ByteArray) {
        val s = ap2 ?: throw NotConnectedException()
        val blocks: List<ByteArray> = when (format) {
            MrpVoice.Format.OPUS -> encoder?.encode(pcm) ?: return
            MrpVoice.Format.LPCM -> listOf(pcm)
        }
        if (!tvRecording) {
            if (tvSaidStop) return
            // Hold up to ~3 s of audio until the TV asks for it.
            synchronized(pending) {
                blocks.forEach { pending.addLast(it) }
                while (pending.size > 150) pending.removeFirst()
            }
            return
        }
        val backlog = synchronized(pending) { pending.toList().also { pending.clear() } }
        if (backlog.isNotEmpty()) Diagnostics.log("siri-voice", "TV is recording; flushing ${backlog.size} buffered blocks")
        val queue = backlog + blocks
        synchronized(pending) {
            // Keep a partial batch for next time so every message has `batch` packets.
            pending.clear()
        }
        val ready = (unsentTail + queue)
        val full = ready.size - ready.size % variant.batch
        for (chunk in ready.subList(0, full).chunked(variant.batch)) {
            s.sendVoicePackets(chunk, sampleTime, format, variant.packing)
            sampleTime += MrpVoice.FRAME_SAMPLES.toLong() * chunk.size
            packetsSent += chunk.size
            if (packetsSent == chunk.size) Diagnostics.log("siri-voice", "first message sent: ${chunk.size} packet(s), ${chunk.sumOf { it.size }} bytes")
        }
        unsentTail = ready.subList(full, ready.size).toList()
    }

    override suspend fun endVoice(session: Int) {
        val s = ap2
        runCatching { if (s != null) sendSiriKey(s, down = false) }
        encoder?.release()
        encoder = null
        val buffered = synchronized(pending) { pending.size.also { pending.clear() } }
        Diagnostics.log(
            "siri-voice",
            "hold #$holdCount (${variant.name}): Siri key up; sent $packetsSent packets, $buffered never requested; TV said recording=$tvRecording stop=$tvSaidStop",
        )
    }


    override suspend fun beginVoicePairing(): PairingHandle {
        val connection = AirPlayConnection(device.host, 7000)
        Diagnostics.log("siri-voice", "voice pairing: opening AirPlay on ${device.host}:7000")
        connection.connect()
        val pairing = try {
            AirPlayAuth.startPairing(connection, "Remote Control Plus")
        } catch (e: Exception) {
            connection.close()
            Diagnostics.log("siri-voice", "voice pairing could not start", e)
            throw PairingException(e.message ?: "Could not start Siri voice pairing")
        }
        Diagnostics.log("siri-voice", "voice pairing: the Apple TV should now show a code")
        return object : PairingHandle {
            override val needsPin = true
            override val instruction = "Enter the 4-digit code now shown on your Apple TV. This second pairing lets the app send your voice to Siri."
            override val storageKey = airplayKey(device)

            override suspend fun finish(pin: String?): String {
                val code = pin?.trim() ?: throw PairingException("A code is required")
                return try {
                    pairing.complete(code).serialize().also { Diagnostics.log("siri-voice", "voice pairing complete") }
                } catch (e: Exception) {
                    Diagnostics.log("siri-voice", "voice pairing failed after code entry", e)
                    throw PairingException(e.message ?: "The Apple TV rejected the code")
                } finally {
                    connection.close()
                }
            }

            override fun cancel() = connection.close()
        }
    }

    companion object {
        fun airplayKey(device: TvDevice) = device.id + "#airplay"
    }
    /** Siri is held like the Siri Remote: down on press, up on release. */
    override suspend fun assistantDown() {
        (remote ?: throw NotConnectedException()).buttonDown(Button.SIRI)
    }

    override suspend fun assistantUp() {
        remote?.buttonUp(Button.SIRI)
    }
    override suspend fun sendText(text: String) {
        val r = remote ?: throw NotConnectedException()
        r.sendText(text, clearPrevious = false)
    }

    override suspend fun beginPairing(): PairingHandle {
        disconnect()
        _state.value = ConnectionState.Connecting
        val port = portResolver(device, 6000) ?: cachedPort(device) ?: device.port
        Diagnostics.log("appletv", "pairing: opening ${device.host}:$port (stored port ${device.port})")
        val client = CompanionClient(device.host, port, scope)
        val session = try {
            client.connect()
            Diagnostics.log("appletv", "pairing: TCP connected, sending pair-setup M1")
            client.startPairing(deviceName = "Remote Control Plus").also {
                Diagnostics.log("appletv", "pairing: M2 received, the Apple TV should now show a code")
            }
        } catch (e: Exception) {
            Diagnostics.log("appletv", "pairing: could not start", e)
            client.close()
            _state.value = ConnectionState.Error(e.message ?: "Pairing failed")
            throw PairingException(e.message ?: "Pairing failed")
        }
        return object : PairingHandle {
            override val needsPin = true
            override val instruction = "Enter the 4-digit code shown on your Apple TV"

            override suspend fun finish(pin: String?): String {
                val code = pin?.trim() ?: throw PairingException("A code is required")
                Diagnostics.log("appletv", "pairing: submitting ${code.length}-digit code")
                return try {
                    session.complete(code).serialize().also { Diagnostics.log("appletv", "pairing: complete") }
                } catch (e: Exception) {
                    Diagnostics.log("appletv", "pairing: failed after code entry", e)
                    throw PairingException(e.message ?: "The Apple TV rejected the code")
                } finally {
                    client.close()
                    _state.value = ConnectionState.Disconnected
                }
            }

            override fun cancel() {
                client.close()
                _state.value = ConnectionState.Disconnected
            }
        }
    }
}
