package com.hqrecorder.app.audio

enum class AudioFormatType { WAV, AAC }

data class AudioQuality(
    val formatType: AudioFormatType,
    val sampleRateHz: Int,
    val bitDepth: Int = 16,
    val aacBitrateBps: Int = 128_000,
    val label: String
) {
    val fileExtension: String
        get() = if (formatType == AudioFormatType.WAV) "wav" else "m4a"

    val qualityTag: String
        get() = when (formatType) {
            AudioFormatType.WAV -> "WAV${sampleRateHz / 1000}-${bitDepth}"
            AudioFormatType.AAC -> "AAC${aacBitrateBps / 1000}"
        }

    companion object {
        val HIGH_WAV = AudioQuality(
            formatType = AudioFormatType.WAV,
            sampleRateHz = 48_000,
            bitDepth = 24,
            label = "高音質 (WAV 48kHz/24bit)"
        )
        val STANDARD_WAV = AudioQuality(
            formatType = AudioFormatType.WAV,
            sampleRateHz = 44_100,
            bitDepth = 16,
            label = "標準 (WAV 44.1kHz/16bit)"
        )
        val LONG_AAC = AudioQuality(
            formatType = AudioFormatType.AAC,
            sampleRateHz = 44_100,
            aacBitrateBps = 128_000,
            label = "長時間・省容量 (AAC 128kbps)"
        )
        val VOICE_AAC = AudioQuality(
            formatType = AudioFormatType.AAC,
            sampleRateHz = 44_100,
            aacBitrateBps = 64_000,
            label = "通話・メモ用 (AAC 64kbps)"
        )

        val presets = listOf(HIGH_WAV, STANDARD_WAV, LONG_AAC, VOICE_AAC)
    }
}
