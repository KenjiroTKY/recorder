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
