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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The lock-screen remote: an Android 16 Live Update (a promoted ongoing
 * notification) with back, play/pause and forward, shown after the remote is
 * used and kept until the user dismisses it. A media session rides along so
 * the phone's volume buttons control the TV. Live Updates must use the
 * standard notification layout, which is why there are only three buttons.
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
                override fun onSkipToPrevious() = skip(forward = false)
                override fun onSkipToNext() = skip(forward = true)
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
            ACTION_PREVIOUS -> skip(forward = false)
            ACTION_NEXT -> skip(forward = true)
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
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_remote)
            .setContentTitle(name)
            .setContentText(if (playing) "Remote ready" else "Paused")
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
        // Android 16 Live Update: ask for the notification to be promoted (top
        // of the lock screen, status-bar chip). Older versions ignore the extra
        // and show an ordinary pinned notification.
        builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        if (android.os.Build.VERSION.SDK_INT >= 36) builder.setShortCriticalText("Remote")
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "lock_screen_remote"
        private const val NOTIFICATION_ID = 41
        /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING (API 36). */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
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
        fun noteActivity(context: Context, visible: Boolean) {
            if (running || !visible) return
            runCatching {
                context.startForegroundService(Intent(context, LockScreenRemoteService::class.java))
            }.onFailure { Diagnostics.log("lockscreen", "could not start the lock-screen remote", it) }
        }
    }
}
