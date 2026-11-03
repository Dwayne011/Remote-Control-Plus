package com.remotecontrolplus.devices

import com.remotecontrolplus.cast.CastClient
import com.remotecontrolplus.core.Capabilities
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.NotConnectedException
import com.remotecontrolplus.core.RemoteKey
import com.remotecontrolplus.core.TextFieldState
import com.remotecontrolplus.core.TvController
import com.remotecontrolplus.core.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Classic Chromecast dongles and Cast-only speakers/displays. There is no
 * on-screen UI to navigate, so only volume, mute and play/pause of whatever
 * is currently casting are offered. "Power" stops the running cast session.
 */
class ChromecastController(
    override val device: TvDevice,
    private val scope: CoroutineScope,
) : TvController {

    override val capabilities = Capabilities(
        keys = setOf(RemoteKey.POWER_OFF, RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.MUTE, RemoteKey.PLAY, RemoteKey.PAUSE),
        textEntry = false,
        volumeSlider = true,
        offLabel = "Stop casting",
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _textField = MutableStateFlow(TextFieldState(focused = false, text = ""))
    override val textField: StateFlow<TextFieldState> = _textField.asStateFlow()

    private var client: CastClient? = null
    private val _powerState = MutableStateFlow<Boolean?>(null)
    override val powerState: StateFlow<Boolean?> = _powerState.asStateFlow()
    private val _volume = MutableStateFlow<Float?>(null)
    override val volume: StateFlow<Float?> = _volume.asStateFlow()
    private var watcher: Job? = null

    override suspend fun connect() {
        _state.value = ConnectionState.Connecting
        try {
            val c = CastClient(device.host, device.port)
            c.connect(scope)
            client = c
            watcher = scope.launch {
                launch {
                    c.receiverStatus.collect { st ->
                        _volume.value = st?.volumeLevel?.toFloat()
                        // "Off" here means stop casting, so it is only live while something casts.
                        _powerState.value = st?.mediaApplication != null
                    }
                }
                c.disconnects.collect { reason ->
                    if (_state.value is ConnectionState.Connected) _state.value = ConnectionState.Error(reason)
                }
            }
            _state.value = ConnectionState.Connected
        } catch (e: Exception) {
            _state.value = ConnectionState.Error(e.message ?: "Could not connect")
        }
    }

    override fun disconnect() {
        watcher?.cancel()
        watcher = null
        client?.close()
        client = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun sendKey(key: RemoteKey) {
        val c = client ?: throw NotConnectedException()
        when (key) {
            RemoteKey.POWER_OFF -> c.stopApplication()
            RemoteKey.VOLUME_UP -> c.adjustVolume(VOLUME_STEP)
            RemoteKey.VOLUME_DOWN -> c.adjustVolume(-VOLUME_STEP)
            RemoteKey.MUTE -> c.toggleMute()
            RemoteKey.PLAY -> c.play()
            RemoteKey.PAUSE -> c.pause()
            else -> Unit
        }
    }

    override suspend fun setVolume(fraction: Float) {
        val c = client ?: throw NotConnectedException()
        c.setVolume(fraction.toDouble())
    }

    override suspend fun sendText(text: String) = Unit

    private companion object {
        const val VOLUME_STEP = 0.05
    }
}
