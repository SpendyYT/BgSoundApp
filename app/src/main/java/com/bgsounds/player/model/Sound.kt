package com.bgsounds.player.model

/**
 * A single background sound bundled inside the app (assets/bgmusic).
 *
 * [audioAssetPath] and [coverAssetPath] are paths relative to the assets root,
 * e.g. "bgmusic/rain_audio.m4a".
 */
data class Sound(
    val id: String,
    val title: String,
    val audioAssetPath: String,
    val coverAssetPath: String?
)
