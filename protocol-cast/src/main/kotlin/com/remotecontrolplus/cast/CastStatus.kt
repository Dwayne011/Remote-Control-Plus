package com.remotecontrolplus.cast

/** The receiver-level state a Cast device reports in RECEIVER_STATUS. */
data class ReceiverStatus(
    val volumeLevel: Double,
    val muted: Boolean,
    val applications: List<CastApplication>,
) {
    /** The application that can take media commands, if any is running. */
    val mediaApplication: CastApplication?
        get() = applications.firstOrNull { Namespaces.MEDIA in it.namespaces && !it.isIdleScreen }
}

data class CastApplication(
    val appId: String,
    val displayName: String,
    val sessionId: String,
    val transportId: String,
    val namespaces: List<String>,
    val isIdleScreen: Boolean,
)

enum class PlayerState { IDLE, PLAYING, PAUSED, BUFFERING, UNKNOWN }

/** What the media application reports in MEDIA_STATUS. */
data class MediaStatus(
    val mediaSessionId: Int,
    val playerState: PlayerState,
    val title: String?,
)
