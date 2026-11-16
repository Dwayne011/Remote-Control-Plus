package com.remotecontrolplus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.core.RemoteKey

/**
 * Receives the notification remote's button presses. Each one is handed
 * straight to the remote's serial command queue, so rapid taps (volume) are
 * sent in order and none are dropped.
 */
class RemoteCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val remote = (context.applicationContext as RemoteControlPlusApp).remote
        val action = intent.action ?: return

        if (action == ACTION_CYCLE_DEVICE) {
            remote.cycleDevice()
            return
        }
        // A button from a notification posted for another device: the device was
        // switched and the notification has not caught up. Do not send it to the wrong TV.
        val target = intent.getStringExtra(EXTRA_DEVICE)
        val current = remote.selected.value?.id
        if (target != null && current != null && target != current) {
            Diagnostics.log("lockscreen", "ignored $action for $target; the selected device is now $current")
            return
        }
        when (action) {
            ACTION_POWER -> remote.enqueuePowerToggle()
            ACTION_PLAY_PAUSE -> remote.enqueuePlayPause()
            else -> KEYS[action]?.let { remote.enqueue(it) }
        }
    }

    companion object {
        const val EXTRA_DEVICE = "com.remotecontrolplus.extra.DEVICE"
        const val ACTION_CYCLE_DEVICE = "com.remotecontrolplus.remote.CYCLE_DEVICE"
        const val ACTION_POWER = "com.remotecontrolplus.remote.POWER"
        const val ACTION_PLAY_PAUSE = "com.remotecontrolplus.remote.PLAY_PAUSE"
        const val ACTION_BACK = "com.remotecontrolplus.remote.BACK"
        const val ACTION_HOME = "com.remotecontrolplus.remote.HOME"
        const val ACTION_DPAD_UP = "com.remotecontrolplus.remote.DPAD_UP"
        const val ACTION_DPAD_DOWN = "com.remotecontrolplus.remote.DPAD_DOWN"
        const val ACTION_DPAD_LEFT = "com.remotecontrolplus.remote.DPAD_LEFT"
        const val ACTION_DPAD_RIGHT = "com.remotecontrolplus.remote.DPAD_RIGHT"
        const val ACTION_DPAD_OK = "com.remotecontrolplus.remote.DPAD_OK"
        const val ACTION_VOL_UP = "com.remotecontrolplus.remote.VOL_UP"
        const val ACTION_VOL_MUTE = "com.remotecontrolplus.remote.VOL_MUTE"
        const val ACTION_VOL_DOWN = "com.remotecontrolplus.remote.VOL_DOWN"

        private val KEYS = mapOf(
            ACTION_BACK to RemoteKey.BACK,
            ACTION_HOME to RemoteKey.HOME,
            ACTION_DPAD_UP to RemoteKey.UP,
            ACTION_DPAD_DOWN to RemoteKey.DOWN,
            ACTION_DPAD_LEFT to RemoteKey.LEFT,
            ACTION_DPAD_RIGHT to RemoteKey.RIGHT,
            ACTION_DPAD_OK to RemoteKey.SELECT,
            ACTION_VOL_UP to RemoteKey.VOLUME_UP,
            ACTION_VOL_MUTE to RemoteKey.MUTE,
            ACTION_VOL_DOWN to RemoteKey.VOLUME_DOWN,
        )
    }
}
