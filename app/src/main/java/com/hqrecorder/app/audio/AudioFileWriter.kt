package com.hqrecorder.app.audio

interface AudioFileWriter {
    fun start(filePath: String, sampleRateHz: Int, channelCount: Int)
    fun writeShortFrame(pcm: ShortArray, frameCount: Int) {}
    fun writeFloatFrame(pcm: FloatArray, frameCount: Int) {}
    fun bytesWritten(): Long
    fun stop(): AudioFileWriterResult
}

data class AudioFileWriterResult(
    val filePath: String,
    val durationMs: Long,
    val fileSizeBytes: Long
)

/** 長時間録音でファイル分割された各パートの再生時間・サイズを1件分に合算する（SPEC.md 3.4）。 */
fun List<AudioFileWriterResult>.totalDurationMs(): Long = sumOf { it.durationMs }

fun List<AudioFileWriterResult>.totalSizeBytes(): Long = sumOf { it.fileSizeBytes }
