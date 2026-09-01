package com.bgsounds.player.tile

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.bgsounds.player.R
import com.bgsounds.player.data.SettingsRepository
import com.bgsounds.player.data.SoundCatalog
import com.bgsounds.player.playback.PlaybackService
import com.bgsounds.player.playback.PlaybackStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Section 7 of the spec: OFF -> ON starts the last selected sound, ON -> OFF
 * stops it. Picking a *different* sound happens from the app's own list
 * (section 8's "standard" fallback) - opening the app is one long-press away
 * since this tile's session activity is MainActivity.
 */
class SoundQsTileService : TileService() {

    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()

        val job = Job()
        listeningJob = job
        val scope = CoroutineScope(Dispatchers.Main.immediate + job)

        // Show a sensible label immediately, even before playback state exists.
        scope.launch {
            val lastId = SettingsRepository(applicationContext).getLastSoundIdOnce()
            if (PlaybackStateHolder.state.value.currentSoundId == null) {
                updateTile(isPlaying = false, soundId = lastId)
            }
        }

        PlaybackStateHolder.state
            .onEach { state -> updateTile(state.isPlaying, state.currentSoundId) }
            .launchIn(scope)
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_TOGGLE_PLAY_PAUSE
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun updateTile(isPlaying: Boolean, soundId: String?) {
        val tile = qsTile ?: return
        val title = soundId
            ?.let { id -> SoundCatalog.loadSounds(applicationContext).firstOrNull { it.id == id }?.title }
            ?: getString(R.string.tile_label)

        tile.label = title
        tile.state = if (isPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
    }
}
