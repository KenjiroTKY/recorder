package com.hqrecorder.app.audio

data class AudioLevel(val leftPeak: Float, val rightPeak: Float, val clipped: Boolean = false)
