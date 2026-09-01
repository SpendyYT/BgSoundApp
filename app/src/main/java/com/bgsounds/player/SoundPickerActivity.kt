package com.bgsounds.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bgsounds.player.data.SoundCatalog
import com.bgsounds.player.model.Sound
import com.bgsounds.player.playback.PlaybackService
import com.bgsounds.player.ui.theme.BgSoundsTheme

/**
 * Reached only from the "Pick Sound" Quick Settings tile via
 * startActivityAndCollapse. Android doesn't let third-party apps render a
 * custom list inline inside the shade the way system tiles (Wi-Fi,
 * Bluetooth) do, so this is the closest equivalent: a small floating dialog
 * that pops up, lets you tap a sound, and closes itself immediately.
 */
class SoundPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sounds = SoundCatalog.loadSounds(applicationContext)

        setContent {
            BgSoundsTheme {
                PickerContent(sounds = sounds, onPick = { sound -> pickSound(sound.id) })
            }
        }
    }

    private fun pickSound(soundId: String) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_SOUND
            putExtra(PlaybackService.EXTRA_SOUND_ID, soundId)
        }
        ContextCompat.startForegroundService(this, intent)
        finish()
    }
}

@Composable
private fun PickerContent(sounds: List<Sound>, onPick: (Sound) -> Unit) {
    Box(modifier = Modifier.padding(24.dp)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                item {
                    Text(
                        text = stringResource(id = R.string.pick_a_sound),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                    )
                }
                items(sounds, key = { it.id }) { sound ->
                    Text(
                        text = sound.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(sound) }
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                }
            }
        }
    }
}
