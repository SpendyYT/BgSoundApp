package com.bgsounds.player.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService
import com.bgsounds.player.R
import com.bgsounds.player.SoundPickerActivity

/**
 * Section 8 of the spec ("pick a sound from Quick Settings"). Android does
 * not let a third-party tile expand its own list inline in the shade the
 * way system tiles do - that's a system-UI-only capability - so this tile's
 * job is simply to pop the small picker dialog (SoundPickerActivity) and
 * collapse the shade, which is the supported way to do this for a normal app.
 *
 * Ships as a *separate* tile from SoundQsTileService so a single tap always
 * does exactly one predictable thing: this one always opens the picker, the
 * other always toggles play/pause. The user can add either or both tiles.
 */
class SoundPickerQsTileService : TileService() {

    override fun onClick() {
        super.onClick()

        val intent = Intent(this, SoundPickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.label = getString(R.string.tile_picker_label)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
            tile.updateTile()
        }
    }
}
