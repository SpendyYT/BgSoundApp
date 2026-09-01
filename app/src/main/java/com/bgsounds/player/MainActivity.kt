package com.bgsounds.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bgsounds.player.data.SettingsRepository
import com.bgsounds.player.data.SoundCatalog
import com.bgsounds.player.model.Sound
import com.bgsounds.player.playback.PlaybackService
import com.bgsounds.player.playback.PlaybackStateHolder
import com.bgsounds.player.ui.loadSampledBitmapFromAsset
import com.bgsounds.player.ui.theme.BgSoundsTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        val sounds = SoundCatalog.loadSounds(applicationContext)
        val settingsRepository = SettingsRepository(applicationContext)

        setContent {
            BgSoundsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BgSoundsScreen(
                        sounds = sounds,
                        settingsRepository = settingsRepository,
                        onSelectSound = { sound -> playSound(sound.id) },
                        onTogglePlayPause = { togglePlayPause() }
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun playSound(soundId: String) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_SOUND
            putExtra(PlaybackService.EXTRA_SOUND_ID, soundId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun togglePlayPause() {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_TOGGLE_PLAY_PAUSE
        }
        ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
private fun BgSoundsScreen(
    sounds: List<Sound>,
    settingsRepository: SettingsRepository,
    onSelectSound: (Sound) -> Unit,
    onTogglePlayPause: () -> Unit
) {
    val playbackState by PlaybackStateHolder.state.collectAsStateWithLifecycle()
    val lastSoundId by settingsRepository.lastSoundId.collectAsStateWithLifecycle(initialValue = null)

    val selectedId = playbackState.currentSoundId ?: lastSoundId
    val selectedSound = sounds.firstOrNull { it.id == selectedId }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(24.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(sounds, key = { it.id }) { sound ->
                SoundRow(
                    sound = sound,
                    isSelected = sound.id == selectedId,
                    onClick = { onSelectSound(sound) }
                )
            }
        }

        val progress = if (playbackState.durationMs > 0) {
            (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        BottomBar(
            title = selectedSound?.title,
            isPlaying = playbackState.isPlaying,
            progress = progress,
            enabled = selectedSound != null,
            onTogglePlayPause = onTogglePlayPause
        )
    }
}

@Composable
private fun SoundRow(sound: Sound, isSelected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val cover = remember(sound.coverAssetPath) {
        sound.coverAssetPath?.let { loadSampledBitmapFromAsset(context, it, 160) }?.asImageBitmap()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) {
            if (cover != null) {
                Image(bitmap = cover, contentDescription = sound.title, modifier = Modifier.fillMaxSize())
            } else {
                Text(text = sound.title.take(1), fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = sound.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun BottomBar(
    title: String?,
    isPlaying: Boolean,
    progress: Float,
    enabled: Boolean,
    onTogglePlayPause: () -> Unit
) {
    Surface(tonalElevation = 4.dp, shadowElevation = 8.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title ?: stringResource(id = R.string.no_sound_selected),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    enabled = enabled,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Just a bar, no timestamps - updates a couple of times a second
            // from PlaybackService, animated smooth in between.
            val animatedProgress by animateFloatAsState(
                targetValue = progress,
                animationSpec = tween(durationMillis = 450, easing = LinearEasing),
                label = "playback_progress"
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
        }
    }
}
