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
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.core.RemoteKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * The notification remote: an ongoing notification with the app's own layout
 * (DecoratedCustomViewStyle), usable from the lock screen without unlocking.
 *
 * Expanded: a device switcher and power on top, then Back / Home / Play-pause,
 * a D-pad with OK, and Volume up / Mute / Volume down. Collapsed: power,
 * play/pause, volume down and volume up. Every button is a stateless trigger
 * that sends a broadcast to [RemoteCommandReceiver]; the notification is only
 * re-posted when the selected device changes.
 *
 * It is shown while the TV is on and removed when it turns off. A media
 * session rides along so the phone's volume buttons control the TV.
 */
class LockScreenRemoteService : Service() {

    private val app get() = application as RemoteControlPlusApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Lock screen remote", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Remote buttons on the lock screen while the TV is on"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        session = MediaSession(this, "RemoteControlPlus").apply {
            // The phone's volume buttons adjust the TV while this session is the active one.
            setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction > 0) app.remote.enqueue(RemoteKey.VOLUME_UP, haptic = false)
                    if (direction < 0) app.remote.enqueue(RemoteKey.VOLUME_DOWN, haptic = false)
                }
            })
            // "Playing" keeps this the session the volume buttons are routed to.
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(0)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build(),
            )
            isActive = true
        }
        startForeground(NOTIFICATION_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        Diagnostics.log("lockscreen", "notification remote shown")

        // Re-post only when the target device (or its name) changes, never on a button press.
        scope.launch {
            app.remote.selected.distinctUntilChangedBy { it?.id to it?.name }.collect {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build())
            }
        }
        // Only while the TV is on. "Off" has to hold for a few seconds, so a
        // reconnect or a device switch (which reads as off for a moment) does not remove it.
        scope.launch {
            app.remote.powerState.collectLatest { on ->
                if (on == false) {
                    delay(OFF_GRACE_MS)
                    Diagnostics.log("lockscreen", "TV is off; removing the notification remote")
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            Diagnostics.log("lockscreen", "notification remote dismissed")
            stopSelf()
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

    private fun build(): Notification {
        val device = app.remote.selected.value
        val name = device?.name ?: "TV"

        /** A broadcast to [RemoteCommandReceiver]; the view id doubles as the unique request code. */
        fun RemoteViews.bind(viewId: Int, action: String) = setOnClickPendingIntent(
            viewId,
            PendingIntent.getBroadcast(
                this@LockScreenRemoteService,
                viewId,
                Intent(this@LockScreenRemoteService, RemoteCommandReceiver::class.java)
                    .setAction(action)
                    .putExtra(RemoteCommandReceiver.EXTRA_DEVICE, device?.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        val collapsed = RemoteViews(packageName, R.layout.notification_remote_collapsed).apply {
            bind(R.id.btn_collapsed_power, RemoteCommandReceiver.ACTION_POWER)
            bind(R.id.btn_collapsed_play_pause, RemoteCommandReceiver.ACTION_PLAY_PAUSE)
            bind(R.id.btn_collapsed_vol_down, RemoteCommandReceiver.ACTION_VOL_DOWN)
            bind(R.id.btn_collapsed_vol_up, RemoteCommandReceiver.ACTION_VOL_UP)
        }
        val expanded = RemoteViews(packageName, R.layout.notification_remote_expanded).apply {
            setTextViewText(R.id.tv_device_name, name)
            bind(R.id.btn_device_switcher, RemoteCommandReceiver.ACTION_CYCLE_DEVICE)
            bind(R.id.btn_power, RemoteCommandReceiver.ACTION_POWER)
            bind(R.id.btn_back, RemoteCommandReceiver.ACTION_BACK)
            bind(R.id.btn_home, RemoteCommandReceiver.ACTION_HOME)
            bind(R.id.btn_play_pause, RemoteCommandReceiver.ACTION_PLAY_PAUSE)
            bind(R.id.btn_dpad_up, RemoteCommandReceiver.ACTION_DPAD_UP)
            bind(R.id.btn_dpad_down, RemoteCommandReceiver.ACTION_DPAD_DOWN)
            bind(R.id.btn_dpad_left, RemoteCommandReceiver.ACTION_DPAD_LEFT)
            bind(R.id.btn_dpad_right, RemoteCommandReceiver.ACTION_DPAD_RIGHT)
            bind(R.id.btn_dpad_ok, RemoteCommandReceiver.ACTION_DPAD_OK)
            bind(R.id.btn_vol_up, RemoteCommandReceiver.ACTION_VOL_UP)
            bind(R.id.btn_vol_mute, RemoteCommandReceiver.ACTION_VOL_MUTE)
            bind(R.id.btn_vol_down, RemoteCommandReceiver.ACTION_VOL_DOWN)
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissed = PendingIntent.getService(
            this, 0, Intent(this, LockScreenRemoteService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_remote)
            .setSubText(name)
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .build()
    }

    companion object {
        private const val CHANNEL = "lock_screen_remote"
        private const val NOTIFICATION_ID = 41
        private const val OFF_GRACE_MS = 8_000L
        private const val ACTION_DISMISS = "com.remotecontrolplus.lock.DISMISS"

        @Volatile private var running = false

        /**
         * The remote was just used: show the notification remote if it is not
         * up. Starting it is only possible while the app is on screen, which
         * is when presses from the app happen.
         */
        fun noteActivity(context: Context, visible: Boolean, tvOn: Boolean?) {
            // Not for a TV that is known to be off; unknown (a plain Chromecast) counts as on.
            if (running || !visible || tvOn == false) return
            runCatching {
                context.startForegroundService(Intent(context, LockScreenRemoteService::class.java))
            }.onFailure { Diagnostics.log("lockscreen", "could not start the notification remote", it) }
        }
    }
}
