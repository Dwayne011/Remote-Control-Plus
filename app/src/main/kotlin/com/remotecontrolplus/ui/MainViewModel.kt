package com.remotecontrolplus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remotecontrolplus.RemoteControlPlusApp
import com.remotecontrolplus.core.Capabilities
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.Pairable
import com.remotecontrolplus.core.PairingHandle
import com.remotecontrolplus.core.RemoteKey
import com.remotecontrolplus.core.TextFieldState
import com.remotecontrolplus.core.TvController
import com.remotecontrolplus.core.TvDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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

sealed interface PairingUiState {
    data object Idle : PairingUiState
    data object Starting : PairingUiState
    data class AwaitingApproval(val instruction: String) : PairingUiState
    data class AwaitingPin(val instruction: String, val inProgress: Boolean = false, val error: String? = null) : PairingUiState
    data class Failed(val message: String) : PairingUiState
    data object Done : PairingUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RemoteControlPlusApp
    private val repository = app.repository
    private val credentials = app.credentials

    val devices: StateFlow<List<TvDevice>> = repository.devices
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selected: StateFlow<TvDevice?> = combine(repository.devices, repository.selectedId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _controller = MutableStateFlow<TvController?>(null)
    val controller: StateFlow<TvController?> = _controller.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = _controller
        .flatMapLatest { it?.state ?: flowOf(ConnectionState.Disconnected) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    val capabilities: StateFlow<Capabilities?> = _controller
        .flatMapLatest { flowOf(it?.capabilities) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val textField: StateFlow<TextFieldState> = _controller
        .flatMapLatest { it?.textField ?: flowOf(TextFieldState(false, "")) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TextFieldState(false, ""))

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

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
            selected.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { device ->
                _controller.value?.disconnect()
                _controller.value = device?.let { app.controllers.create(it, credentials.get(it.id)) }
                _controller.value?.connect()
            }
        }
    }

    // ------------------------------------------------------------- actions

    fun selectDevice(id: String) = viewModelScope.launch { repository.select(id) }

    fun forgetDevice(id: String) = viewModelScope.launch {
        if (selected.value?.id == id) {
            _controller.value?.disconnect()
            _controller.value = null
        }
        repository.forget(id)
    }

    fun reconnect() = viewModelScope.launch {
        val device = selected.value ?: return@launch
        _controller.value?.disconnect()
        _controller.value = app.controllers.create(device, credentials.get(device.id))
        _controller.value?.connect()
    }

    fun press(key: RemoteKey) = viewModelScope.launch {
        val c = _controller.value ?: return@launch
        runCatching { c.sendKey(key) }.onFailure { _messages.tryEmit(it.message ?: "Command failed") }
    }

    fun sendText(text: String) = viewModelScope.launch {
        val c = _controller.value ?: return@launch
        runCatching { c.sendText(text) }.onFailure { _messages.tryEmit(it.message ?: "Could not send text") }
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
            _messages.tryEmit("${device.name} added")
        }
    }

    fun startPairing(device: TvDevice) = viewModelScope.launch {
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
            c.beginPairing()
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
            val credential = handle.finish(pin)
            credentials.put(device.id, credential)
            _pairing.value = PairingUiState.Done
            pairingController?.disconnect()
            pairingController = null
            pairingHandle = null
            repository.select(device.id)
            // The selection may not change when re-pairing the current device.
            if (selected.value?.id == device.id) reconnect()
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
        _controller.value?.disconnect()
        pairingController?.disconnect()
    }
}
