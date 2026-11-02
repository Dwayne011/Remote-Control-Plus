package com.remotecontrolplus.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.graphics.vector.ImageVector
import com.remotecontrolplus.core.DeviceType

/**
 * Per-device glyphs so the remote resembles the one the device shipped with:
 * the Siri Remote's chevron and TV button, Samsung's return arrow, and
 * Google Home's arrow and house for everything Google.
 */
data class DeviceSkin(
    val backIcon: ImageVector,
    val backLabel: String,
    val homeIcon: ImageVector,
    val homeLabel: String,
)

fun DeviceType.skin(): DeviceSkin = when (this) {
    DeviceType.APPLE_TV -> DeviceSkin(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", Icons.Filled.Tv, "TV")
    DeviceType.SAMSUNG -> DeviceSkin(Icons.AutoMirrored.Filled.KeyboardReturn, "Return", Icons.Filled.Home, "Home")
    else -> DeviceSkin(Icons.AutoMirrored.Filled.ArrowBack, "Back", Icons.Filled.Home, "Home")
}
