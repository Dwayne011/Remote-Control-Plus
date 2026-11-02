package dev.amrakshay.atvremote.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import remote.Remotemessage.RemoteDirection
import remote.Remotemessage.RemoteKeyCode
import java.io.Closeable

enum class TransportKind {
    /** The TV's own remote service, over TLS on 6466. */
    Native,

    /** adbd, for the many TV devices that ship without that service. */
    Adb,
}

/**
 * What a transport can actually do, so the UI can say so rather than offering
 * a control that quietly does nothing.
 */
data class Capabilities(
    val kind: TransportKind,
    /** The device reports state changes by itself; nothing has to poll for them. */
    val pushesState: Boolean,
    /** The device says when a text field is focused, and what it holds. */
    val announcesTextFields: Boolean,
    /** Sending text replaces the field's contents rather than typing at the cursor. */
    val replacesFieldText: Boolean,
    /** The device can be asked which apps it has. */
    val listsApps: Boolean,
)

/** An app to launch: a deep link for [TransportKind.Native], a component for ADB. */
data class RemoteApp(val label: String, val target: String)

/**
 * One device, however we reach it.
 *
 * [RemoteSession] speaks the TV's own protocol; [AdbRemote] drives adbd. The app
 * above this interface does not care which, beyond reading [capabilities] to
 * decide what to show.
 */
interface Remote : Closeable {

    val capabilities: Capabilities

    val volume: StateFlow<VolumeInfo?>
    val currentApp: StateFlow<String>
    val device: StateFlow<DeviceInfo?>

    /** What the focused field holds. Empty unless [Capabilities.announcesTextFields]. */
    val textField: StateFlow<String>

    /** Whether typing will reach anything. */
    val imeReady: StateFlow<Boolean>

    /** Empty unless [Capabilities.listsApps]; the UI falls back to its own list. */
    val apps: StateFlow<List<RemoteApp>>

    /** Emits when the connection drops, so the UI can reconnect rather than error. */
    val disconnects: SharedFlow<String>

    suspend fun connect(scope: CoroutineScope)

    fun sendKey(key: RemoteKeyCode, direction: RemoteDirection = RemoteDirection.SHORT)

    fun sendText(text: String)

    fun launchApp(target: String)
}
