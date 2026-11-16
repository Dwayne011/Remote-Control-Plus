package com.remotecontrolplus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.IBinder
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.core.RemoteKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The lock-screen remote: a media-player card (media session plus MediaStyle
 * notification) with back, play/pause, forward and close, shown after the
 * remote is used and kept until closed. Android hides a media card it thinks
 * is paused or idle, so the session always reports "playing" and play/pause is
 * a custom action whose icon follows the button. The same session lets the
 * phone's volume buttons control the TV.
 */
class LockScreenRemoteService : Service() {

    private val app get() = application as RemoteControlPlusApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession

    /** Our best guess; the TV does not report playback, so it follows the button. */
    private var playing = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Lock screen remote", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Remote buttons on the lock screen after you use the remote"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        session = MediaSession(this, "RemoteControlPlus").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onCustomAction(action: String, extras: Bundle?) = handle(action)
            })
            // The phone's volume buttons adjust the TV while this session is the active one.
            setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction > 0) act(RemoteKey.VOLUME_UP, haptic = false)
                    if (direction < 0) act(RemoteKey.VOLUME_DOWN, haptic = false)
                }
            })
            isActive = true
        }
        refresh(first = true)
        Diagnostics.log("lockscreen", "lock-screen remote shown")

        scope.launch { app.remote.selected.collect { refresh() } }
        // Only while the TV is on. "Off" has to hold for a few seconds, so a
        // reconnect blip (which reads as off on Samsung and LG) does not remove the card.
        scope.launch {
            app.remote.powerState.collectLatest { on ->
                if (on == false) {
                    delay(OFF_GRACE_MS)
                    Diagnostics.log("lockscreen", "TV is off; removing the lock-screen remote")
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { handle(it) }
        return START_NOT_STICKY
    }

    private fun handle(action: String) {
        when (action) {
            ACTION_DISMISS -> {
                Diagnostics.log("lockscreen", "lock-screen remote closed")
                stopSelf()
            }
            ACTION_PLAY_PAUSE -> {
                playing = !playing
                act(if (playing) RemoteKey.PLAY else RemoteKey.PAUSE)
                refresh()
            }
            ACTION_PREVIOUS -> skip(forward = false)
            ACTION_NEXT -> skip(forward = true)
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    /** Send [key], reconnecting first if the connection dropped while the phone was locked. */
    private fun act(key: RemoteKey, haptic: Boolean = true) = withConnection("$key") { it.press(key, haptic) }

    /** Back or forward: a 10-second skip where the TV supports it, else its rewind/forward key. */
    private fun skip(forward: Boolean) = withConnection(if (forward) "forward" else "back") { it.skip(forward) }

    private fun withConnection(what: String, send: (com.remotecontrolplus.data.RemoteManager) -> Unit) {
        scope.launch {
            val remote = app.remote
            if (remote.connectionState.value !is ConnectionState.Connected) {
                Diagnostics.log("lockscreen", "$what pressed while ${remote.connectionState.value}; reconnecting first")
                withTimeoutOrNull(10_000) { remote.reconnect().join() }
            }
            send(remote)
        }
    }

    private fun refresh(first: Boolean = false) {
        val device = app.remote.selected.value
        val name = device?.name ?: "TV"
        val subtitle = device?.type?.label ?: "Remote Control Plus"
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .build(),
        )
        fun custom(action: String, label: String, icon: Int) =
            PlaybackState.CustomAction.Builder(action, label, icon).build()
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(0)
                .addCustomAction(custom(ACTION_PREVIOUS, "Back", R.drawable.ic_n_prev))
                .addCustomAction(
                    if (playing) custom(ACTION_PLAY_PAUSE, "Pause", R.drawable.ic_n_pause)
                    else custom(ACTION_PLAY_PAUSE, "Play", R.drawable.ic_n_play),
                )
                .addCustomAction(custom(ACTION_NEXT, "Forward", R.drawable.ic_n_next))
                .addCustomAction(custom(ACTION_DISMISS, "Close", R.drawable.ic_n_close))
                // Always "playing": Android drops a paused or idle media card from the lock screen.
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        val notification = build(name, subtitle)
        if (first) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun build(name: String, subtitle: String): Notification {
        fun service(action: String) = PendingIntent.getService(
            this, action.hashCode(), Intent(this, LockScreenRemoteService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(icon: Int, title: String, action: String) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), title, service(action)).build()

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Android 13+ draws the buttons from the media session; these are for Android 12.
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_remote)
            .setContentTitle(name)
            .setContentText(subtitle)
            .setContentIntent(open)
            .setDeleteIntent(service(ACTION_DISMISS))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(action(R.drawable.ic_n_prev, "Back", ACTION_PREVIOUS))
            .addAction(
                if (playing) action(R.drawable.ic_n_pause, "Pause", ACTION_PLAY_PAUSE)
                else action(R.drawable.ic_n_play, "Play", ACTION_PLAY_PAUSE),
            )
            .addAction(action(R.drawable.ic_n_next, "Forward", ACTION_NEXT))
            .addAction(action(R.drawable.ic_n_close, "Close", ACTION_DISMISS))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    companion object {
        private const val CHANNEL = "lock_screen_remote"
        private const val NOTIFICATION_ID = 41
        private const val OFF_GRACE_MS = 8_000L
        private const val ACTION_DISMISS = "com.remotecontrolplus.lock.DISMISS"
        private const val ACTION_PLAY_PAUSE = "com.remotecontrolplus.lock.PLAY_PAUSE"
        private const val ACTION_PREVIOUS = "com.remotecontrolplus.lock.PREVIOUS"
        private const val ACTION_NEXT = "com.remotecontrolplus.lock.NEXT"

        @Volatile private var running = false

        /**
         * The remote was just used: show the lock-screen remote if it is not
         * up. Starting it is only possible while the app is on screen, which
         * is when presses from the app happen.
         */
        fun noteActivity(context: Context, visible: Boolean, tvOn: Boolean?) {
            // Not for a TV that is known to be off; unknown (a plain Chromecast) counts as on.
            if (running || !visible || tvOn == false) return
            runCatching {
                context.startForegroundService(Intent(context, LockScreenRemoteService::class.java))
            }.onFailure { Diagnostics.log("lockscreen", "could not start the lock-screen remote", it) }
        }
    }
}
