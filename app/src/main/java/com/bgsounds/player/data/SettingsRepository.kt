package com.bgsounds.player.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bgsounds_settings")
private val LAST_SOUND_ID = stringPreferencesKey("last_sound_id")

/**
 * Persists only what the spec asks for: the id of the last selected sound,
 * so the app (and the QS tile) remember it across restarts.
 */
class SettingsRepository(private val context: Context) {

    val lastSoundId: Flow<String?> = context.dataStore.data.map { it[LAST_SOUND_ID] }

    suspend fun getLastSoundIdOnce(): String? = lastSoundId.first()

    suspend fun setLastSoundId(soundId: String) {
        context.dataStore.edit { prefs -> prefs[LAST_SOUND_ID] = soundId }
    }
}
