package com.bgsounds.player.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlaybackUiState(
    val currentSoundId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** Null when no sleep timer is running. */
    val sleepTimerRemainingMs: Long? = null
)

/**
 * Lightweight in-process pub/sub for playback state. The app is single-process,
 * so PlaybackService (the source of truth) can publish here and both the
 * Compose UI and the Quick Settings tile can observe it without binding to the
 * service or connecting a MediaController.
 */
object PlaybackStateHolder {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    fun updatePlayback(soundId: String?, isPlaying: Boolean, positionMs: Long = 0L, durationMs: Long = 0L) {
        _state.update { current ->
            current.copy(
                currentSoundId = soundId,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs
            )
        }
    }

    fun updateSleepTimer(remainingMs: Long?) {
        _state.update { it.copy(sleepTimerRemainingMs = remainingMs) }
    }

    fun reset() {
        _state.value = PlaybackUiState()
    }
}
