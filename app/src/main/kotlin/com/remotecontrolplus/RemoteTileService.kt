package com.remotecontrolplus

import android.app.Dialog
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.ui.mini.MiniRemote
import com.remotecontrolplus.ui.theme.RemoteControlPlusTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Quick Settings tile. Tapping it shows a compact remote over the shade,
 * with a button to the full app; the subtitle names the selected TV.
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
        if (app.remote.selected.value == null) {
            openFullRemote()
            return
        }
        app.remote.ensureConnected("tile")

        val owner = DialogOwner()
        val dialog = Dialog(this).apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window?.setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                RemoteControlPlusTheme {
                    MiniRemote(
                        remote = app.remote,
                        onDismiss = { dialog.dismiss() },
                        onOpenFull = {
                            dialog.dismiss()
                            openFullRemote()
                        },
                    )
                }
            }
        }
        dialog.setContentView(view)
        dialog.setOnDismissListener { owner.destroy() }
        owner.create()
        showDialog(dialog)
    }

    private fun openFullRemote() {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    /**
     * Compose needs lifecycle, saved-state and view-model owners on the view
     * tree. A TileService dialog has none, so this supplies a minimal set
     * that lives exactly as long as the dialog.
     */
    private class DialogOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        fun create() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
