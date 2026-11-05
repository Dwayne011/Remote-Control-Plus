package com.remotecontrolplus.data

import com.remotecontrolplus.core.Capabilities
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.DeviceType
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.core.Haptics
import com.remotecontrolplus.core.RemoteKey
import com.remotecontrolplus.core.TextFieldState
import com.remotecontrolplus.core.TvController
import com.remotecontrolplus.core.TvDevice
import com.remotecontrolplus.core.HoldableAssistant
import com.remotecontrolplus.core.VoiceCapable
import com.remotecontrolplus.core.VoiceRecorder
import com.remotecontrolplus.devices.ControllerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one live connection to the selected TV, owned by the Application so
 * the full-screen remote and the Quick Settings mini remote share it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteManager(
    private val scope: CoroutineScope,
    private val repository: DeviceRepository,
    private val credentials: CredentialStore,
    private val controllers: ControllerFactory,
) {
    val devices: StateFlow<List<TvDevice>> = repository.devices
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val selected: StateFlow<TvDevice?> = combine(repository.devices, repository.selectedId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _controller = MutableStateFlow<TvController?>(null)
    val controller: StateFlow<TvController?> = _controller.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = _controller
        .flatMapLatest { it?.state ?: flowOf(ConnectionState.Disconnected) }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    val capabilities: StateFlow<Capabilities?> = _controller
        .flatMapLatest { flowOf(it?.capabilities) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val textField: StateFlow<TextFieldState> = _controller
        .flatMapLatest { it?.textField ?: flowOf(TextFieldState(false, "")) }
        .stateIn(scope, SharingStarted.Eagerly, TextFieldState(false, ""))

    val volume: StateFlow<Float?> = _controller
        .flatMapLatest { it?.volume ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val padMode: StateFlow<String> = repository.padMode
        .stateIn(scope, SharingStarted.Eagerly, "swipe")

    private val _waking = MutableStateFlow(false)
    /** True while retrying the connection after sending a wake packet. */
    val waking: StateFlow<Boolean> = _waking.asStateFlow()

    /**
     * Whether the selected TV is on. Samsung and LG only answer when they are
     * on, so connection state is the answer; Google TV reports it; others are unknown.
     */
    val powerState: StateFlow<Boolean?> = combine(
        _controller.flatMapLatest { it?.powerState ?: flowOf(null) },
        connectionState,
        selected,
    ) { reported, connection, device ->
        when (device?.type) {
            DeviceType.SAMSUNG, DeviceType.LG -> connection is ConnectionState.Connected
            else -> reported
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    init {
        scope.launch {
            selected.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { device ->
                _controller.value?.disconnect()
                if (device != null) {
                    Diagnostics.log(
                        "app",
                        "selected ${device.type.label} '${device.name}' (${device.host}:${device.port}) " +
                            "credential=${Diagnostics.redact(credentials.get(device.id))} mac=${device.mac ?: "unknown"}",
                    )
                }
                _controller.value = device?.let { controllers.create(it, credentials.get(it.id)) }
                _controller.value?.let { c -> withContext(Dispatchers.IO) { c.connect() } }
            }
        }
        scope.launch {
            connectionState.collect { Diagnostics.log("app", "connection state: $it") }
        }
    }

    fun selectDevice(id: String) = scope.launch {
        Haptics.click()
        repository.select(id)
    }

    fun renameDevice(id: String, name: String) = scope.launch {
        if (name.isNotBlank()) repository.rename(id, name)
    }

    fun forgetDevice(id: String) = scope.launch {
        if (selected.value?.id == id) {
            _controller.value?.disconnect()
            _controller.value = null
        }
        repository.forget(id)
    }

    fun setPadMode(mode: String) = scope.launch {
        Haptics.tick()
        repository.setPadMode(mode)
    }

    fun reconnect(): Job = scope.launch {
        val device = selected.value ?: return@launch
        _controller.value?.disconnect()
        _controller.value = controllers.create(device, credentials.get(device.id))
        _controller.value?.let { c -> withContext(Dispatchers.IO) { c.connect() } }
    }

    /** Pick the connection back up if it dropped while the app was away. */
    fun ensureConnected(reason: String) {
        val state = connectionState.value
        if (_controller.value != null && state !is ConnectionState.Connected && state !is ConnectionState.Connecting &&
            state !is ConnectionState.PairingRequired
        ) {
            Diagnostics.log("app", "$reason with state $state; reconnecting")
            reconnect()
        }
    }

    fun press(key: RemoteKey, haptic: Boolean = true) = scope.launch {
        val c = _controller.value ?: return@launch
        if (haptic) Haptics.click()
        Diagnostics.log("app", "press $key on ${c.device.name}")
        runCatching { withContext(Dispatchers.IO) { c.sendKey(key) } }.onFailure {
            Diagnostics.log("app", "$key failed", it)
            _messages.tryEmit(it.message ?: "Command failed")
        }
        // A TV woken from standby takes a while to boot; keep trying to connect for a bit.
        if (key == RemoteKey.POWER_ON && connectionState.value !is ConnectionState.Connected) {
            if (c.device.mac == null) {
                _messages.tryEmit("No MAC address known for this TV, so it cannot be woken over the network")
                return@launch
            }
            _messages.tryEmit("Wake packet sent to ${c.device.name}")
            _waking.value = true
            try {
                for (delayMs in listOf(5_000L, 7_000L, 8_000L, 10_000L)) {
                    delay(delayMs)
                    if (connectionState.value is ConnectionState.Connected) return@launch
                    if (_controller.value?.device?.id != c.device.id) return@launch
                    Diagnostics.log("app", "retrying connection after wake")
                    reconnect().join()
                }
                if (connectionState.value !is ConnectionState.Connected) {
                    _messages.tryEmit("${c.device.name} did not come online. Check Power On with Mobile is enabled on the TV.")
                }
            } finally {
                _waking.value = false
            }
        }
    }

    fun swipe(direction: RemoteKey) = scope.launch {
        val c = _controller.value ?: return@launch
        Haptics.tick()
        Diagnostics.log("app", "swipe $direction on ${c.device.name}")
        runCatching { withContext(Dispatchers.IO) { c.swipe(direction) } }.onFailure {
            Diagnostics.log("app", "swipe failed", it)
            _messages.tryEmit(it.message ?: "Command failed")
        }
    }

    fun setVolume(fraction: Float) = scope.launch {
        Haptics.tick()
        val c = _controller.value ?: return@launch
        runCatching { withContext(Dispatchers.IO) { c.setVolume(fraction) } }.onFailure {
            Diagnostics.log("app", "set volume failed", it)
            _messages.tryEmit(it.message ?: "Could not set volume")
        }
    }


    // ------------------------------------------------------------ assistant

    private val _listening = MutableStateFlow(false)
    /** True while the phone microphone is streaming to the TV, or Siri is held. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private var assistantJob: Job? = null

    /**
     * Assistant button pressed. With [micAllowed], a Google TV gets the phone
     * microphone streamed until [assistantReleased]; an Apple TV has Siri held
     * down; anything else just gets its assistant key.
     */
    fun assistantPressed(micAllowed: Boolean) {
        val c = _controller.value ?: return
        Haptics.click()
        assistantJob?.cancel()
        assistantJob = scope.launch {
            when {
                c is VoiceCapable && micAllowed -> streamVoice(c)
                c is HoldableAssistant -> runCatching {
                    withContext(Dispatchers.IO) { c.assistantDown() }
                    _listening.value = true
                    Diagnostics.log("app", "assistant held on ${c.device.name}")
                }.onFailure { _messages.tryEmit(it.message ?: "Could not start Siri") }
                else -> {
                    if (c is VoiceCapable) _messages.tryEmit("Allow microphone access to talk to the TV from your phone")
                    runCatching { withContext(Dispatchers.IO) { c.sendKey(RemoteKey.ASSISTANT) } }
                        .onFailure { _messages.tryEmit(it.message ?: "Command failed") }
                }
            }
        }
    }

    fun assistantReleased() {
        val c = _controller.value
        val job = assistantJob
        assistantJob = null
        scope.launch {
            job?.cancel()
            job?.join()
            if (c is HoldableAssistant && c !is VoiceCapable && _listening.value) {
                runCatching { withContext(Dispatchers.IO) { c.assistantUp() } }
                Diagnostics.log("app", "assistant released on ${c.device.name}")
            }
            _listening.value = false
        }
    }


    /** Whether holding the assistant button would use the phone microphone. */
    fun wantsMicrophone(): Boolean = _controller.value is VoiceCapable
    private suspend fun streamVoice(c: VoiceCapable) {
        val session = try {
            withContext(Dispatchers.IO) { c.beginVoice() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.log("voice", "could not open a voice session", e)
            _messages.tryEmit(e.message ?: "The TV did not accept voice")
            return
        }
        _listening.value = true
        try {
            VoiceRecorder(c.voiceSampleRate, c.voiceChunkBytes).record { chunk ->
                c.sendVoice(session, chunk)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Button released: the normal way out.
        } catch (e: Exception) {
            Diagnostics.log("voice", "recording failed", e)
            _messages.tryEmit(e.message ?: "Microphone failed")
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                runCatching { c.endVoice(session) }
            }
            _listening.value = false
        }
    }
    fun sendText(text: String) = scope.launch {
        val c = _controller.value ?: return@launch
        Diagnostics.log("app", "send ${text.length} chars of text to ${c.device.name}")
        runCatching { withContext(Dispatchers.IO) { c.sendText(text) } }.onFailure {
            Diagnostics.log("app", "text failed", it)
            _messages.tryEmit(it.message ?: "Could not send text")
        }
    }
}
