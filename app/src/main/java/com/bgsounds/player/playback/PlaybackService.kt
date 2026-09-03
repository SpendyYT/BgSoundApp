package com.bgsounds.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.bgsounds.player.MainActivity
import com.bgsounds.player.R
import com.bgsounds.player.data.SettingsRepository
import com.bgsounds.player.data.SoundCatalog
import com.bgsounds.player.model.Sound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The only place that touches ExoPlayer. Runs as a foreground media-playback
 * service so a locked screen / closed Activity never stops the sound, and
 * exposes a MediaSession so the system media notification, lock screen
 * controls and Bluetooth/headset buttons all work.
 *
 * Foreground-service lifecycle, in short:
 * - `onStartCommand` ALWAYS calls `startForeground()` synchronously first,
 *   before doing anything else, because Android enforces a strict timeout
 *   after `startForegroundService()` and doesn't care whether the requested
 *   action turns out to actually start playback.
 * - As soon as playback is not actually running (paused, or the command
 *   turned out to be a no-op), we demote back out of the foreground state
 *   with `stopForeground(STOP_FOREGROUND_DETACH)` - the notification stays
 *   (so resuming is one tap away) but the process is no longer pinned as an
 *   active foreground service, and Android is free to reclaim it.
 * - If it then sits paused/idle for a while, we fully stop the service so
 *   nothing is left running or shown at all - this is what fixes the
 *   "app still looks like it's running after I stopped it" complaint.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sounds: List<Sound>
    private lateinit var notificationManager: NotificationManagerCompat

    // Default dispatcher here is Main.immediate on purpose: every call in this
    // class that touches `player` must run on the main thread, and this way
    // any `serviceScope.launch { ... }` block does that by default. Only the
    // DataStore write is explicitly pushed to Dispatchers.IO.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var positionTickerJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var idleStopJob: Job? = null
    private var isForegroundService = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        notificationManager = NotificationManagerCompat.from(this)

        settingsRepository = SettingsRepository(applicationContext)
        sounds = SoundCatalog.loadSounds(applicationContext)

        player = ExoPlayer.Builder(this).build().apply {
            // Infinite repeat of the current item is Media3's built-in gapless
            // loop: it just re-queues the same sample without a stop/start
            // round trip, so per the spec we try this first and only reach
            // for a manual crossfade if a specific file turns out to click.
            repeatMode = Player.REPEAT_MODE_ONE
            setWakeMode(C.WAKE_MODE_LOCAL)
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishState(isPlaying)
                if (isPlaying) {
                    idleStopJob?.cancel()
                    promoteToForeground()
                    startPositionTicker()
                } else {
                    stopPositionTicker()
                    demoteFromForeground()
                    scheduleIdleAutoStop()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                publishState(player.isPlaying)
                updateNotification()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateNotification()
            }
        })

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen synchronously, before anything else - see class doc.
        startForeground(NOTIFICATION_ID, buildNotification())
        isForegroundService = true
        idleStopJob?.cancel()

        when (intent?.action) {
            ACTION_PLAY_SOUND -> intent.getStringExtra(EXTRA_SOUND_ID)?.let { playSound(it) }
            ACTION_TOGGLE_PLAY_PAUSE -> togglePlayPause()
            ACTION_STOP -> stopPlaybackFully()
            ACTION_SET_SLEEP_TIMER -> {
                val minutes = intent.getIntExtra(EXTRA_SLEEP_TIMER_MINUTES, 0)
                if (minutes > 0) startSleepTimer(minutes * 60_000L) else cancelSleepTimer()
            }
            ACTION_CANCEL_SLEEP_TIMER -> cancelSleepTimer()
        }

        // Whatever just happened, if we're not actually playing there's no
        // reason to keep occupying the "active foreground service" slot.
        if (!player.isPlaying && isForegroundService) {
            demoteFromForeground()
            scheduleIdleAutoStop()
        }
        return START_STICKY
    }

    private fun playSound(soundId: String) {
        val sound = sounds.firstOrNull { it.id == soundId } ?: return

        if (player.currentMediaItem?.mediaId != sound.id) {
            val mediaItem = MediaItem.Builder()
                .setMediaId(sound.id)
                .setUri("asset:///${sound.audioAssetPath}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(sound.title)
                        .setArtist(getString(R.string.app_name))
                        .build()
                )
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
        }
        player.play()
        serviceScope.launch(Dispatchers.IO) { settingsRepository.setLastSoundId(sound.id) }
    }

    private fun togglePlayPause() {
        if (player.currentMediaItem == null) {
            serviceScope.launch {
                val soundId = settingsRepository.getLastSoundIdOnce() ?: sounds.firstOrNull()?.id
                if (soundId != null) playSound(soundId)
            }
            return
        }
        if (player.isPlaying) player.pause() else player.play()
    }

    /** Full stop: releases playback, clears the sleep timer, removes the notification. */
    private fun stopPlaybackFully() {
        idleStopJob?.cancel()
        idleStopJob = null
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        stopPositionTicker()

        player.stop()
        player.clearMediaItems()
        PlaybackStateHolder.reset()

        if (isForegroundService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundService = false
        } else {
            notificationManager.cancel(NOTIFICATION_ID)
        }
        stopSelf()
    }

    // --- Sleep timer -------------------------------------------------------

    private fun startSleepTimer(durationMs: Long) {
        sleepTimerJob?.cancel()
        sleepTimerJob = serviceScope.launch {
            var remaining = durationMs
            while (isActive && remaining > 0) {
                PlaybackStateHolder.updateSleepTimer(remaining)
                updateNotification()
                delay(1_000)
                remaining -= 1_000
            }
            if (isActive) {
                PlaybackStateHolder.updateSleepTimer(null)
                stopPlaybackFully()
            }
        }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        PlaybackStateHolder.updateSleepTimer(null)
        updateNotification()
    }

    // --- Foreground state / notification -----------------------------------

    private fun promoteToForeground() {
        if (!isForegroundService) {
            startForeground(NOTIFICATION_ID, buildNotification())
            isForegroundService = true
        } else {
            updateNotification()
        }
    }

    private fun demoteFromForeground() {
        if (isForegroundService) {
            // DETACH: keep showing the (now dismissible) notification so the
            // user can resume with one tap, but stop counting this service as
            // an active foreground service.
            stopForeground(STOP_FOREGROUND_DETACH)
            isForegroundService = false
        }
        updateNotification()
    }

    private fun scheduleIdleAutoStop() {
        idleStopJob?.cancel()
        idleStopJob = serviceScope.launch {
            delay(IDLE_AUTO_STOP_MS)
            if (!player.isPlaying) {
                stopPlaybackFully()
            }
        }
    }

    private fun publishState(isPlaying: Boolean) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        PlaybackStateHolder.updatePlayback(
            soundId = player.currentMediaItem?.mediaId,
            isPlaying = isPlaying,
            positionMs = player.currentPosition,
            durationMs = duration
        )
    }

    /** Refreshes position/duration a couple of times a second while playing, for the progress bar. */
    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = serviceScope.launch {
            while (isActive) {
                publishState(player.isPlaying)
                delay(500)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    private fun buildNotification(): Notification {
        val session = mediaSession
        val title = player.mediaMetadata.title?.toString() ?: getString(R.string.app_name)
        val sleepTimerRemaining = PlaybackStateHolder.state.value.sleepTimerRemainingMs
        val subtitle = if (sleepTimerRemaining != null) {
            getString(R.string.notification_timer_subtitle, formatCountdown(sleepTimerRemaining))
        } else {
            getString(R.string.app_name)
        }

        val toggleAction = if (player.isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_notification_pause,
                getString(R.string.action_pause),
                pendingIntentForAction(ACTION_TOGGLE_PLAY_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_notification_play,
                getString(R.string.action_play),
                pendingIntentForAction(ACTION_TOGGLE_PLAY_PAUSE)
            )
        }
        val stopAction = NotificationCompat.Action(
            R.drawable.ic_notification_stop,
            getString(R.string.action_stop),
            pendingIntentForAction(ACTION_STOP)
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_tile)
            .setOngoing(player.isPlaying)
            .setOnlyAlertOnce(true)
            .setContentIntent(session?.sessionActivity)
            .addAction(toggleAction)
            .addAction(stopAction)

        if (session != null) {
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1)
            )
        }

        return builder.build()
    }

    private fun pendingIntentForAction(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun updateNotification() {
        if (isForegroundService) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        } else if (player.currentMediaItem != null) {
            // Paused-but-not-stopped: still show a plain (dismissible) notification.
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun formatCountdown(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) {
            stopPlaybackFully()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopPositionTicker()
        sleepTimerJob?.cancel()
        idleStopJob?.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY_SOUND = "com.bgsounds.player.action.PLAY_SOUND"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.bgsounds.player.action.TOGGLE_PLAY_PAUSE"
        const val ACTION_STOP = "com.bgsounds.player.action.STOP"
        const val ACTION_SET_SLEEP_TIMER = "com.bgsounds.player.action.SET_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.bgsounds.player.action.CANCEL_SLEEP_TIMER"
        const val EXTRA_SOUND_ID = "sound_id"
        const val EXTRA_SLEEP_TIMER_MINUTES = "sleep_timer_minutes"

        private const val CHANNEL_ID = "bg_sounds_playback"
        private const val NOTIFICATION_ID = 1
        private const val IDLE_AUTO_STOP_MS = 10 * 60 * 1000L // 10 minutes paused -> fully stop
    }
}
