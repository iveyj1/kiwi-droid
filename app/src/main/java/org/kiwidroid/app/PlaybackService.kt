package org.kiwidroid.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.kiwidroid.core.KiwiSession
import org.kiwidroid.core.formatKhz

/** Owns the connection, audio focus, notification, and screen-off resource lifetime. */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val radio get() = (application as KiwiApplication).radio
    private lateinit var focus: AudioFocus
    private lateinit var mediaSession: MediaSession
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sessions: Job? = null
    private var notifications: Job? = null
    private var lastStartId = 0
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Radio playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background KiwiSDR listening and Stop control"
                setSound(null, null)
            })
        focus = AudioFocus(this) { radio.stop(it) }
        mediaSession = MediaSession(this, "KiwiDroid").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPause() = radio.stop()
                override fun onStop() = radio.stop()
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP) {
            radio.stop()
            if (sessions == null) finishPlayback()
            return START_NOT_STICKY
        }
        // Promote before requesting audio focus (also required for target API 35+).
        mediaSession.isActive = true
        val initial = notification(summary())
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTIFICATION_ID, initial)
        foreground = true
        if (sessions == null) {
            val session = KiwiSession()
            sessions = scope.launch {
                // Cancellation is joined before another receiver can own AudioTrack/the network slot.
                radio.requests.collectLatest { request ->
                    if (request == null) {
                        finishPlayback()
                        return@collectLatest
                    }
                    try {
                        acquireLocks()
                        focus.acquire()
                        withContext(Dispatchers.IO) {
                            session.run(request.receiver, radio.tuning,
                                AndroidAudioSink { radio.state.value.settings.sound }) { metrics ->
                                scope.launch { radio.received(request, metrics) }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        radio.failed(request, e.message ?: "Playback failed")
                    } finally {
                        focus.release()
                    }
                }
            }
            notifications = scope.launch {
                // Do not rebuild notifications at RSSI frame cadence.
                radio.state.map { Summary(it.settings.receiver.name,
                    "${formatKhz(it.settings.tuning.frequencyHz)} kHz · ${it.settings.tuning.mode.name}", it.status) }
                    .distinctUntilChanged().collect { info ->
                        if (foreground) updateNotification(info)
                    }
            }
        }
        // Never resume a receiver automatically after system kill/reboot.
        return START_NOT_STICKY
    }

    // Android 13 notification permission explicitly exempts MediaSession notifications:
    // https://developer.android.com/develop/ui/views/notifications/notification-permission#exemptions
    @SuppressLint("NotificationPermission")
    private fun updateNotification(info: Summary) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(info))
    }

    private data class Summary(val receiver: String, val tune: String, val status: String)
    private fun summary(): Summary = radio.state.value.let {
        Summary(it.settings.receiver.name,
            "${formatKhz(it.settings.tuning.frequencyHz)} kHz · ${it.settings.tuning.mode.name}", it.status)
    }

    private fun notification(info: Summary): Notification {
        mediaSession.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, info.receiver)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, info.tune)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, info.tune).build())
        mediaSession.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE)
            .setState(if (info.status == "Playing") PlaybackState.STATE_PLAYING else PlaybackState.STATE_BUFFERING,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build())
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(info.receiver)
            .setContentText("${info.tune} · ${info.status}")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stop),
                "Stop", stop).build())
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0))
            .build()
    }

    @SuppressLint("WakelockTimeout") // Intentionally held for active listening; released on stop/error/destroy.
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (cpuLock == null) cpuLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KiwiDroid:Playback").apply { setReferenceCounted(false) }
        if (cpuLock?.isHeld == false) cpuLock?.acquire()
        if (wifiLock == null) wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KiwiDroid:Streaming")
            ?.apply { setReferenceCounted(false) }
        if (wifiLock?.isHeld == false) wifiLock?.acquire()
    }

    private fun releaseLocks() {
        if (wifiLock?.isHeld == true) wifiLock?.release()
        if (cpuLock?.isHeld == true) cpuLock?.release()
    }

    private fun finishPlayback() {
        foreground = false
        mediaSession.isActive = false
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    override fun onDestroy() {
        foreground = false
        scope.cancel()
        focus.release()
        releaseLocks()
        mediaSession.release()
        if (radio.state.value.running) radio.stop("Playback service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PLAY = "org.kiwidroid.app.PLAY"
        const val ACTION_STOP = "org.kiwidroid.app.STOP"
        const val NOTIFICATION_ID = 1
        private const val CHANNEL = "playback"
    }
}
