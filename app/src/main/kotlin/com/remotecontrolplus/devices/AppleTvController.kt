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
import dev.atvremote.protocol.airplay.AirPlayAuth
import dev.atvremote.protocol.airplay.AirPlayConnection
import dev.atvremote.protocol.companion.AppleTvRemote
import dev.atvremote.protocol.companion.Button
import dev.atvremote.protocol.companion.CompanionClient
import dev.atvremote.protocol.hap.Credentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        // Experimental: present the Apple TV's own home-level Siri values back
        // to it (see AppleTvRemote.peerSiriInfo). They are learnt on the first
        // connection, so that one reconnects once to present them.
        val hadPeerSiri = peerSiriCache[device.id] != null
        r.peerSiriInfo = peerSiriCache[device.id]
        r.onPeerSiriInfo = { peerSiriCache[device.id] = it }
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
        r.onRequest = { name, content ->
            logSiriTraffic("request", name, content)
            if (name == "_siriEndpoint") {
                endpointRequested = true
                emptyMap()
            } else {
                Diagnostics.log("appletv", "unhandled request from TV: $name")
                null
            }
        }
        r.onEvent = { name, content ->
            logSiriTraffic("event", name, content)
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
        if (!hadPeerSiri && peerSiriCache[device.id] != null) {
            Diagnostics.log("siri-voice", "learnt the Apple TV's Siri details; reconnecting once to present them")
            r.onDisconnect = null
            r.close()
            remote = null
            return attempt(credentials, port)
        }
        if (hadPeerSiri) Diagnostics.log("siri-voice", "presented iPhone-style Siri details built from the Apple TV's")
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
    // Over Companion Link, as the iPhone's Control Center remote does it
    // (captures in pyatv issues #2325 and #2875): Siri held via _hidC, then
    // _siriStart, then _siA events carrying Opus frames, then _siriStop. The
    // captured frames start with TOC 0x48: Opus config 9, SILK wideband
    // (16 kHz), 20 ms, mono, one frame per packet (RFC 6716 table 2).
    // Publicly, replaying this opened Siri and moved the waveform, but no
    // speech was recognised, so every step is logged.

    override val voiceSampleRate = VOICE_RATE
    override val voiceChunkBytes = VOICE_FRAME_SAMPLES * 2

    private var encoder: OpusEncoder? = null
    private var siriStartNanos = 0L
    private val unsent = ArrayList<ByteArray>()
    private var framesSent = 0
    private var eventsSent = 0
    private var bytesSent = 0
    private var holdCount = 0
    // Loudness of the audio since the last _siA, for key 4.
    private var levelSumSquares = 0.0
    private var levelSamples = 0
    private val sentLevels = ArrayList<Float>()
    private val tocCounts = java.util.TreeMap<Int, Int>()

    /**
     * One test per hold, in rotation. 0.4.16: with the level scaled up, Siri
     * recognised some words, so the open questions are quiet microphone audio
     * (Android's VOICE_RECOGNITION source applies no gain) and the level.
     */
    private enum class LevelVariant(val label: String, val levelScale: Float, val audioGain: Float) {
        BASELINE("audio as recorded, level x30", 30f, 1f),
        GAIN4("audio boosted x4, level x30", 30f, 4f),
        GAIN4_X100("audio boosted x4, level x100", 100f, 4f),
    }
    private var levelVariant = LevelVariant.BASELINE
    @Volatile private var endpointRequested = false

    /** Log anything Siri-related the Apple TV says to us during a hold. */
    private fun logSiriTraffic(kind: String, name: String, content: Map<Any?, Any?>) {
        if (name.startsWith("_si", ignoreCase = true) || name.contains("siri", ignoreCase = true)) {
            Diagnostics.log("siri-voice", "TV $kind $name: $content")
        }
    }

    override suspend fun beginVoice(): Int {
        val r = remote ?: throw NotConnectedException()
        holdCount++
        levelVariant = LevelVariant.entries[(holdCount - 1) % LevelVariant.entries.size]
        endpointRequested = false
        synchronized(unsent) { unsent.clear() }
        framesSent = 0; eventsSent = 0; bytesSent = 0
        synchronized(tocCounts) { tocCounts.clear() }
        synchronized(unsent) { levelSumSquares = 0.0; levelSamples = 0; sentLevels.clear() }
        encoder = OpusEncoder(VOICE_RATE, VOICE_BITRATE)
        r.buttonDown(Button.SIRI)
        Diagnostics.log("siri-voice", "hold #$holdCount (test ${levelVariant.ordinal + 1} of 3: ${levelVariant.label}): Siri button down (_hidC 10); sending _siriStart")
        val response = runCatching { r.siriStart() }
        siriStartNanos = System.nanoTime()
        response
            .onSuccess { Diagnostics.log("siri-voice", "_siriStart answered: ${it["_c"]}") }
            .onFailure { Diagnostics.log("siri-voice", "_siriStart failed: ${it.message}") }
        return holdCount
    }

    override suspend fun sendVoice(session: Int, pcm: ByteArray) {
        val r = remote ?: throw NotConnectedException()
        val gain = levelVariant.audioGain
        val audio = if (gain == 1f) pcm else boost(pcm, gain)
        val packets = encoder?.encode(audio) ?: return
        synchronized(unsent) {
            var i = 0
            while (i + 1 < pcm.size) {
                val v = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768.0
                levelSumSquares += v * v
                levelSamples++
                i += 2
            }
        }
        if (packets.isEmpty()) return
        synchronized(tocCounts) {
            for (p in packets) if (p.isNotEmpty()) tocCounts.merge(p[0].toInt() and 0xFF, 1, Int::plus)
        }
        val batch: List<ByteArray>? = synchronized(unsent) {
            unsent += packets
            if (unsent.size >= FRAMES_PER_EVENT) unsent.toList().also { unsent.clear() } else null
        }
        if (batch != null) sendBatch(r, batch)
    }

    private fun sendBatch(r: AppleTvRemote, frames: List<ByteArray>) {
        val seconds = (System.nanoTime() - siriStartNanos) / 1_000_000_000.0
        // Key 4 in the captures starts near 1e-6 and rises to ~0.045 while
        // talking, so it reads as a normalised RMS level, not a timestamp.
        val level = synchronized(unsent) {
            val l = if (levelSamples > 0) kotlin.math.sqrt(levelSumSquares / levelSamples).toFloat() else 0f
            levelSumSquares = 0.0; levelSamples = 0
            sentLevels += l
            l
        }
        // The level is measured on the recorded audio, before any boost.
        val sent: Float = (level * levelVariant.levelScale).coerceAtMost(1f)
        synchronized(unsent) { sentLevels[sentLevels.size - 1] = sent }
        r.siriAudio(frames, sent)
        framesSent += frames.size
        eventsSent++
        bytesSent += frames.sumOf { it.size }
        if (eventsSent == 1) {
            Diagnostics.log("siri-voice", "first _siA sent: ${frames.size} frames, sizes ${frames.map { it.size }}, level sent %.6f, t=%.3f s".format(sent, seconds))
        }
    }

    /** Gain with a soft limiter (tanh), so loud speech does not clip harshly. */
    private fun boost(pcm: ByteArray, gain: Float): ByteArray {
        val out = ByteArray(pcm.size)
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768f
            val y = (kotlin.math.tanh(v * gain) * 32767f).toInt()
            out[i] = (y and 0xFF).toByte()
            out[i + 1] = ((y shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    override suspend fun endVoice(session: Int) {
        val r = remote
        val rest = synchronized(unsent) { unsent.toList().also { unsent.clear() } }
        if (r != null && rest.isNotEmpty()) runCatching { sendBatch(r, rest) }
        encoder?.release()
        encoder = null
        if (r != null) {
            runCatching { r.siriStop() }
                .onSuccess { Diagnostics.log("siri-voice", "_siriStop answered: ${it["_c"]}") }
                .onFailure { Diagnostics.log("siri-voice", "_siriStop failed: ${it.message}") }
            runCatching { r.buttonUp(Button.SIRI) }
        }
        Diagnostics.log(
            "siri-voice",
            "hold #$holdCount (${levelVariant.label}): released; $eventsSent _siA events, $framesSent frames, $bytesSent bytes; TV asked for endpoint=$endpointRequested",
        )
        val tocs = synchronized(tocCounts) { tocCounts.entries.joinToString { (t, n) -> "0x%02x (%s) x%d".format(t, describeToc(t), n) } }
        Diagnostics.log("siri-voice", "Opus frame types this hold: $tocs; the iPhone capture is all 0x48")
        val levels = synchronized(unsent) { sentLevels.toList() }
        if (levels.isNotEmpty()) {
            Diagnostics.log(
                "siri-voice",
                "levels sent (key 4): min %.6f, max %.6f, first 10: %s".format(
                    levels.min(), levels.max(), levels.take(10).joinToString { "%.4f".format(it) },
                ),
            )
        }
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
        /** The Apple TV's own _siriInfo per device, for this app process. */
        private val peerSiriCache = java.util.concurrent.ConcurrentHashMap<String, Map<Any?, Any?>>()
        const val VOICE_RATE = 16_000
        const val VOICE_FRAME_SAMPLES = 320
        const val VOICE_BITRATE = 16_000
        const val FRAMES_PER_EVENT = 5
        fun airplayKey(device: TvDevice) = device.id + "#airplay"

        /** Opus TOC byte in words (RFC 6716 section 3.1, table 2). */
        fun describeToc(toc: Int): String {
            val config = toc shr 3
            val mode = when (config) {
                in 0..3 -> "SILK NB"; in 4..7 -> "SILK MB"; in 8..11 -> "SILK WB"
                12, 13 -> "Hybrid SWB"; 14, 15 -> "Hybrid FB"
                in 16..19 -> "CELT NB"; in 20..23 -> "CELT WB"; in 24..27 -> "CELT SWB"; else -> "CELT FB"
            }
            val ms = when {
                config < 12 -> listOf("10", "20", "40", "60")[config % 4]
                config < 16 -> listOf("10", "20")[config % 2]
                else -> listOf("2.5", "5", "10", "20")[config % 4]
            }
            return "$mode $ms ms ${if (toc and 4 != 0) "stereo" else "mono"} code ${toc and 3}"
        }
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
