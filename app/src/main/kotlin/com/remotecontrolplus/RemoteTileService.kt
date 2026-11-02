package com.remotecontrolplus

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.remotecontrolplus.core.Diagnostics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Quick Settings tile. Tapping it collapses the shade and opens the pop-up
 * remote ([QuickRemoteActivity]); the subtitle names the selected TV.
 */
class RemoteTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val app = application as RemoteControlPlusApp
        val device = runCatching {
            runBlocking {
                val devices = app.repository.devices.first()
                val id = app.repository.selectedId.first()
                devices.firstOrNull { it.id == id } ?: devices.firstOrNull()
            }
        }.getOrNull()
        qsTile?.apply {
            label = getString(R.string.tile_label)
            subtitle = device?.name ?: getString(R.string.tile_no_device)
            state = if (device != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        Diagnostics.log("tile", "quick settings tile tapped")
        val app = application as RemoteControlPlusApp
        val target = if (app.remote.selected.value == null) MainActivity::class.java else QuickRemoteActivity::class.java
        val intent = Intent(this, target).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
