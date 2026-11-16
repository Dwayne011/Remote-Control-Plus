package com.remotecontrolplus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.widget.RemoteViews
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
 * The lock-screen remote: an ongoing notification with the app's own layout —
 * a D-pad with OK, play/pause and close — whose buttons work without
 * unlocking. Collapsed it is one row of the same buttons; expanded it has the
 * round pad. It is shown while the TV is on and removed when it turns off. A
 * media session rides along so the phone's volume buttons control the TV.
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
            else -> KEYS[action]?.let { act(it) }
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
        // "Playing" keeps this the session the phone's volume buttons go to.
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(0)
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

    private fun service(action: String) = PendingIntent.getService(
        this, action.hashCode(), Intent(this, LockScreenRemoteService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Wire the buttons both layouts share. */
    private fun views(layout: Int) = RemoteViews(packageName, layout).apply {
        setOnClickPendingIntent(R.id.n_up, service(ACTION_UP))
        setOnClickPendingIntent(R.id.n_down, service(ACTION_DOWN))
        setOnClickPendingIntent(R.id.n_left, service(ACTION_LEFT))
        setOnClickPendingIntent(R.id.n_right, service(ACTION_RIGHT))
        setOnClickPendingIntent(R.id.n_ok, service(ACTION_OK))
        setOnClickPendingIntent(R.id.n_play_pause, service(ACTION_PLAY_PAUSE))
        setOnClickPendingIntent(R.id.n_close, service(ACTION_DISMISS))
        setImageViewResource(R.id.n_play_pause, if (playing) R.drawable.ic_n_pause else R.drawable.ic_n_play)
    }

    private fun build(name: String, subtitle: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val big = views(R.layout.notification_remote_big).apply {
            setTextViewText(R.id.n_title, name)
            setTextViewText(R.id.n_subtitle, subtitle)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_remote)
            .setSubText(name)
            .setContentIntent(open)
            .setDeleteIntent(service(ACTION_DISMISS))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views(R.layout.notification_remote_small))
            .setCustomBigContentView(big)
            .build()
    }

    companion object {
        private const val CHANNEL = "lock_screen_remote"
        private const val NOTIFICATION_ID = 41
        private const val OFF_GRACE_MS = 8_000L
        private const val ACTION_DISMISS = "com.remotecontrolplus.lock.DISMISS"
        private const val ACTION_PLAY_PAUSE = "com.remotecontrolplus.lock.PLAY_PAUSE"
        private const val ACTION_UP = "com.remotecontrolplus.lock.UP"
        private const val ACTION_DOWN = "com.remotecontrolplus.lock.DOWN"
        private const val ACTION_LEFT = "com.remotecontrolplus.lock.LEFT"
        private const val ACTION_RIGHT = "com.remotecontrolplus.lock.RIGHT"
        private const val ACTION_OK = "com.remotecontrolplus.lock.OK"
        private val KEYS = mapOf(
            ACTION_UP to RemoteKey.UP,
            ACTION_DOWN to RemoteKey.DOWN,
            ACTION_LEFT to RemoteKey.LEFT,
            ACTION_RIGHT to RemoteKey.RIGHT,
            ACTION_OK to RemoteKey.SELECT,
        )

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
