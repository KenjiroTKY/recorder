package com.hqrecorder.app.settings

import com.hqrecorder.app.audio.AudioQuality

data class AppSettings(
    val quality: AudioQuality = AudioQuality.STANDARD_WAV,
    val saveFolderUri: String? = null,
    val certificateEnabled: Boolean = false,
    val tsaUrl: String = "",
    val tsaAuthHeader: String? = null,
    val audioFocusPolicy: AudioFocusPolicy = AudioFocusPolicy.PAUSE
)
