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

/**
 * The only place that touches ExoPlayer. Runs as a foreground media-playback
 * service so a locked screen / closed Activity never stops the sound, and
 * exposes a MediaSession so the system media notification, lock screen
 * controls and Bluetooth/headset buttons all work.
 *
 * IMPORTANT: `startForeground()` is called synchronously and unconditionally
 * at the top of every `onStartCommand`, instead of waiting for Media3's
 * automatic "promote to foreground on player event" mechanism. That
 * mechanism only fires on a *change* in player state (e.g. isPlaying
 * flipping) - if the requested sound was already playing, nothing changes,
 * no event fires, startForeground() never gets called, and Android kills the
 * process a few seconds later with ForegroundServiceDidNotStartInTimeException.
 * Calling it ourselves, immediately, with a real notification, avoids that
 * entirely and is also what actually makes the notification (and therefore
 * the shade's media controls) appear reliably.
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
                updateNotification()
                if (isPlaying) startPositionTicker() else stopPositionTicker()
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

        when (intent?.action) {
            ACTION_PLAY_SOUND -> intent.getStringExtra(EXTRA_SOUND_ID)?.let { playSound(it) }
            ACTION_TOGGLE_PLAY_PAUSE -> togglePlayPause()
            ACTION_STOP -> stopPlayback()
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
        updateNotification()
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
        updateNotification()
    }

    private fun stopPlayback() {
        player.stop()
        publishState(false)
        stopPositionTicker()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishState(isPlaying: Boolean) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        PlaybackStateHolder.update(
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_tile)
            .setOngoing(player.isPlaying)
            .setOnlyAlertOnce(true)
            .setContentIntent(session?.sessionActivity)
            .addAction(toggleAction)

        if (session != null) {
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0)
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
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopPositionTicker()
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
        const val EXTRA_SOUND_ID = "sound_id"

        private const val CHANNEL_ID = "bg_sounds_playback"
        private const val NOTIFICATION_ID = 1
    }
}
