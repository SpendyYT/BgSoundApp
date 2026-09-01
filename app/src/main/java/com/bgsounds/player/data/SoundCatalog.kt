package com.bgsounds.player.data

import android.content.Context
import com.bgsounds.player.model.Sound
import java.util.Locale

private const val ASSETS_DIR = "bgmusic"
private const val AUDIO_SUFFIX = "_audio.m4a"
private const val COVER_SUFFIX = "_cover.png"

/**
 * Builds the list of available sounds straight from the files packaged under
 * assets/bgmusic. Adding or removing a "<name>_audio.m4a" (+ optional
 * "<name>_cover.png") in that folder is enough to change what shows up in the app -
 * no other code needs to change.
 */
object SoundCatalog {

    @Volatile
    private var cached: List<Sound>? = null

    fun loadSounds(context: Context): List<Sound> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }

            val assetManager = context.applicationContext.assets
            val files = assetManager.list(ASSETS_DIR)?.toList().orEmpty()
            val coverFiles = files.filter { it.endsWith(COVER_SUFFIX) }.toSet()

            val sounds = files
                .filter { it.endsWith(AUDIO_SUFFIX) }
                .map { audioFile ->
                    val baseName = audioFile.removeSuffix(AUDIO_SUFFIX)
                    val coverFile = "$baseName$COVER_SUFFIX"
                    Sound(
                        id = baseName,
                        title = humanize(baseName),
                        audioAssetPath = "$ASSETS_DIR/$audioFile",
                        coverAssetPath = if (coverFile in coverFiles) "$ASSETS_DIR/$coverFile" else null
                    )
                }
                .sortedBy { it.title.lowercase(Locale.getDefault()) }

            cached = sounds
            return sounds
        }
    }

    private fun humanize(baseName: String): String =
        baseName.split('_', '-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString() }
            }
}
