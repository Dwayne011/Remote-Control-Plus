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
import android.os.SystemClock
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.core.RemoteKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The lock-screen remote: a media-style notification with previous, play/pause,
 * next and volume, backed by a media session so the phone's volume buttons
 * control the TV. It appears after the remote is used and removes itself after
 * [IDLE_MS] without a button press, or when swiped away.
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
                override fun onPlay() { playing = true; act(RemoteKey.PLAY); refresh() }
                override fun onPause() { playing = false; act(RemoteKey.PAUSE); refresh() }
                override fun onSkipToPrevious() = act(RemoteKey.PREVIOUS)
                override fun onSkipToNext() = act(RemoteKey.NEXT)
                override fun onCustomAction(action: String, extras: Bundle?) = when (action) {
                    ACTION_VOL_DOWN -> act(RemoteKey.VOLUME_DOWN)
                    ACTION_VOL_UP -> act(RemoteKey.VOLUME_UP)
                    else -> Unit
                }
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
        scope.launch {
            while (true) {
                delay(60_000)
                if (SystemClock.elapsedRealtime() - lastActivity >= IDLE_MS) {
                    Diagnostics.log("lockscreen", "no button press for 30 minutes; removing the lock-screen remote")
                    if (!app.isVisible) app.remote.disconnectIdle()
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                Diagnostics.log("lockscreen", "lock-screen remote swiped away")
                stopSelf()
            }
            ACTION_PLAY_PAUSE -> {
                playing = !playing
                act(if (playing) RemoteKey.PLAY else RemoteKey.PAUSE)
                refresh()
            }
            ACTION_PREVIOUS -> act(RemoteKey.PREVIOUS)
            ACTION_NEXT -> act(RemoteKey.NEXT)
            ACTION_VOL_DOWN -> act(RemoteKey.VOLUME_DOWN)
            ACTION_VOL_UP -> act(RemoteKey.VOLUME_UP)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    /** Send [key], reconnecting first if the connection dropped while the phone was locked. */
    private fun act(key: RemoteKey, haptic: Boolean = true) {
        lastActivity = SystemClock.elapsedRealtime()
        scope.launch {
            val remote = app.remote
            if (remote.connectionState.value !is ConnectionState.Connected) {
                Diagnostics.log("lockscreen", "$key pressed while ${remote.connectionState.value}; reconnecting first")
                withTimeoutOrNull(10_000) { remote.reconnect().join() }
            }
            remote.press(key, haptic)
        }
    }

    private fun refresh(first: Boolean = false) {
        val name = app.remote.selected.value?.name ?: "TV"
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Remote Control Plus")
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT,
                )
                .addCustomAction(PlaybackState.CustomAction.Builder(ACTION_VOL_DOWN, "Volume down", R.drawable.ic_n_vol_down).build())
                .addCustomAction(PlaybackState.CustomAction.Builder(ACTION_VOL_UP, "Volume up", R.drawable.ic_n_vol_up).build())
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    0f,
                )
                .build(),
        )
        val notification = build(name)
        if (first) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun build(name: String): Notification {
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
            .setContentText("Remote Control Plus")
            .setContentIntent(open)
            .setDeleteIntent(service(ACTION_DISMISS))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setShowWhen(false)
            .addAction(action(R.drawable.ic_n_prev, "Previous", ACTION_PREVIOUS))
            .addAction(
                if (playing) action(R.drawable.ic_n_pause, "Pause", ACTION_PLAY_PAUSE)
                else action(R.drawable.ic_n_play, "Play", ACTION_PLAY_PAUSE),
            )
            .addAction(action(R.drawable.ic_n_next, "Next", ACTION_NEXT))
            .addAction(action(R.drawable.ic_n_vol_down, "Volume down", ACTION_VOL_DOWN))
            .addAction(action(R.drawable.ic_n_vol_up, "Volume up", ACTION_VOL_UP))
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
        private const val IDLE_MS = 30 * 60_000L
        private const val ACTION_DISMISS = "com.remotecontrolplus.lock.DISMISS"
        private const val ACTION_PLAY_PAUSE = "com.remotecontrolplus.lock.PLAY_PAUSE"
        private const val ACTION_PREVIOUS = "com.remotecontrolplus.lock.PREVIOUS"
        private const val ACTION_NEXT = "com.remotecontrolplus.lock.NEXT"
        private const val ACTION_VOL_DOWN = "com.remotecontrolplus.lock.VOL_DOWN"
        private const val ACTION_VOL_UP = "com.remotecontrolplus.lock.VOL_UP"

        @Volatile private var running = false
        @Volatile private var lastActivity = 0L

        /**
         * The remote was just used. Restarts the idle timer, and shows the
         * lock-screen remote if it is not up; that is only possible while the
         * app is on screen, which is when presses from the app happen.
         */
        fun noteActivity(context: Context, visible: Boolean) {
            lastActivity = SystemClock.elapsedRealtime()
            if (running || !visible) return
            runCatching {
                context.startForegroundService(Intent(context, LockScreenRemoteService::class.java))
            }.onFailure { Diagnostics.log("lockscreen", "could not start the lock-screen remote", it) }
        }
    }
}
