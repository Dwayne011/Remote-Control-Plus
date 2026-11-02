package com.remotecontrolplus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.ui.mini.MiniRemote
import com.remotecontrolplus.ui.theme.RemoteControlPlusTheme

/**
 * The pop-up remote launched from the Quick Settings tile: a translucent
 * activity with a full-width card at the bottom over whatever was on
 * screen. Tap outside, swipe down, or press back to dismiss.
 */
class QuickRemoteActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as RemoteControlPlusApp
        Diagnostics.log("tile", "quick remote opened")
        app.remote.ensureConnected("quick remote")

        setContent {
            RemoteControlPlusTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .navigationBarsPadding()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { /* swallow taps so they do not dismiss */ },
                    ) {
                        MiniRemote(
                            remote = app.remote,
                            onOpenFull = {
                                startActivity(
                                    Intent(this@QuickRemoteActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                                )
                                finish()
                            },
                            onDismiss = { finish() },
                        )
                    }
                }
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

}
