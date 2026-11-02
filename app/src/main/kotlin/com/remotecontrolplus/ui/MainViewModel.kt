package com.remotecontrolplus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remotecontrolplus.RemoteControlPlusApp
import com.remotecontrolplus.core.Capabilities
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.core.Pairable
import com.remotecontrolplus.core.PairingHandle
import com.remotecontrolplus.core.RemoteKey
import com.remotecontrolplus.core.TextFieldState
import com.remotecontrolplus.core.TvController
import com.remotecontrolplus.core.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PairingUiState {
    data object Idle : PairingUiState
    data object Starting : PairingUiState
    data class AwaitingApproval(val instruction: String) : PairingUiState
    data class AwaitingPin(val instruction: String, val inProgress: Boolean = false, val error: String? = null) : PairingUiState
    data class Failed(val message: String) : PairingUiState
    data object Done : PairingUiState
}

/**
 * Screen-level state: discovery and pairing live here; the live connection
 * is the app-wide [com.remotecontrolplus.data.RemoteManager], shared with
 * the Quick Settings mini remote.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RemoteControlPlusApp
    private val repository = app.repository
    private val credentials = app.credentials
    private val remote = app.remote

    val devices: StateFlow<List<TvDevice>> get() = remote.devices
    val selected: StateFlow<TvDevice?> get() = remote.selected
    val controller: StateFlow<TvController?> get() = remote.controller
    val connectionState: StateFlow<ConnectionState> get() = remote.connectionState
    val capabilities: StateFlow<Capabilities?> get() = remote.capabilities
    val textField: StateFlow<TextFieldState> get() = remote.textField
    val volume: StateFlow<Float?> get() = remote.volume
    val padMode: StateFlow<String> get() = remote.padMode
    val waking: StateFlow<Boolean> get() = remote.waking
    val powerState: StateFlow<Boolean?> get() = remote.powerState
    val messages: SharedFlow<String> get() = remote.messages

    fun setPadMode(mode: String) = remote.setPadMode(mode)
    fun swipe(direction: RemoteKey) = remote.swipe(direction)
    fun setVolume(fraction: Float) = remote.setVolume(fraction)
    fun selectDevice(id: String) = remote.selectDevice(id)
    fun forgetDevice(id: String) = remote.forgetDevice(id)
    fun reconnect() = remote.reconnect()
    fun press(key: RemoteKey) = remote.press(key)
    fun sendText(text: String) = remote.sendText(text)

    /** Called when the app returns to the foreground. */
    fun onForeground() = remote.ensureConnected("foreground")

    // ------------------------------------------------------------ discovery

    private val _discovered = MutableStateFlow<List<TvDevice>>(emptyList())
    val discovered: StateFlow<List<TvDevice>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var scanJob: Job? = null

    // -------------------------------------------------------------- pairing

    private val _pairing = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val pairing: StateFlow<PairingUiState> = _pairing.asStateFlow()

    private val _pairingDevice = MutableStateFlow<TvDevice?>(null)
    val pairingDevice: StateFlow<TvDevice?> = _pairingDevice.asStateFlow()

    private var pairingHandle: PairingHandle? = null
    private var pairingController: TvController? = null

    init {
        viewModelScope.launch {
            pairing.collect { if (it != PairingUiState.Idle) Diagnostics.log("app", "pairing state: $it") }
        }
    }

    fun startScan() {
        if (_scanning.value) return
        scanJob = viewModelScope.launch {
            _scanning.value = true
            _discovered.value = emptyList()
            try {
                app.discovery.scan().collect { device ->
                    _discovered.value = (_discovered.value.filterNot { it.id == device.id } + device)
                        .sortedBy { it.name.lowercase() }
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    /** Save a discovered device and, if it needs pairing, start that straight away. */
    fun addDevice(device: TvDevice) = viewModelScope.launch {
        repository.save(device)
        val probe = app.controllers.create(device, null)
        if (probe is Pairable) {
            startPairing(device)
        } else {
            repository.select(device.id)
            remote.message("${device.name} added")
        }
    }

    fun startPairing(device: TvDevice) = viewModelScope.launch {
        if (_pairingDevice.value?.id == device.id && _pairing.value !is PairingUiState.Idle &&
            _pairing.value !is PairingUiState.Failed && _pairing.value != PairingUiState.Done
        ) {
            Diagnostics.log("app", "pairing already in progress for ${device.name}; ignoring second start")
            return@launch
        }
        Diagnostics.log("app", "start pairing with ${device.type.label} '${device.name}'")
        cancelPairing(silent = true)
        _pairingDevice.value = device
        _pairing.value = PairingUiState.Starting

        // Pair on a fresh controller so the live one is not disturbed.
        val c = app.controllers.create(device, credentials.get(device.id))
        if (c !is Pairable) {
            _pairing.value = PairingUiState.Done
            return@launch
        }
        pairingController = c
        val handle = try {
            withContext(Dispatchers.IO) { c.beginPairing() }
        } catch (e: Exception) {
            _pairing.value = PairingUiState.Failed(e.message ?: "Could not start pairing")
            return@launch
        }
        pairingHandle = handle
        if (handle.needsPin) {
            _pairing.value = PairingUiState.AwaitingPin(handle.instruction)
        } else {
            _pairing.value = PairingUiState.AwaitingApproval(handle.instruction)
            complete(null)
        }
    }

    fun submitPin(pin: String) = viewModelScope.launch {
        val current = _pairing.value as? PairingUiState.AwaitingPin ?: return@launch
        _pairing.value = current.copy(inProgress = true, error = null)
        complete(pin)
    }

    private suspend fun complete(pin: String?) {
        val device = _pairingDevice.value ?: return
        val handle = pairingHandle ?: return
        try {
            val credential = withContext(Dispatchers.IO) { handle.finish(pin) }
            credentials.put(device.id, credential)
            _pairing.value = PairingUiState.Done
            pairingController?.disconnect()
            pairingController = null
            pairingHandle = null
            repository.select(device.id)
            // The selection may not change when re-pairing the current device.
            if (selected.value?.id == device.id) remote.reconnect()
        } catch (e: Exception) {
            val message = e.message ?: "Pairing failed"
            _pairing.value = when (val s = _pairing.value) {
                is PairingUiState.AwaitingPin -> s.copy(inProgress = false, error = message)
                else -> PairingUiState.Failed(message)
            }
        }
    }

    fun retryPairing() {
        _pairingDevice.value?.let { startPairing(it) }
    }

    fun cancelPairing(silent: Boolean = false) {
        pairingHandle?.cancel()
        pairingHandle = null
        pairingController?.disconnect()
        pairingController = null
        _pairing.value = PairingUiState.Idle
        if (!silent) _pairingDevice.value = null
    }

    override fun onCleared() {
        pairingController?.disconnect()
    }
}
