package com.remotecontrolplus.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.ui.graphics.vector.ImageVector
import com.remotecontrolplus.core.DeviceType

fun DeviceType.icon(): ImageVector = when (this) {
    DeviceType.SAMSUNG -> Icons.Filled.Tv
    DeviceType.LG -> Icons.Outlined.Tv
    DeviceType.ROKU -> Icons.Filled.LiveTv
    DeviceType.APPLE_TV -> Icons.Filled.ConnectedTv
    DeviceType.GOOGLE_TV -> Icons.Outlined.SmartDisplay
    DeviceType.CHROMECAST -> Icons.Filled.Cast
}
