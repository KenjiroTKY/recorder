package com.hqrecorder.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * PCM(16bit)フレームをMediaCodecでAAC-LCへエンコードし、MediaMuxerでm4aへ書き出す。
 */
class AacFileWriter(private val bitrateBps: Int) : AudioFileWriter {

    private lateinit var codec: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var filePath = ""
    private var sampleRateHz = 44_100
    private var channelCount = 2
    private var totalFramesEncoded = 0L
    private var presentationTimeUs = 0L
    private val bufferInfo = MediaCodec.BufferInfo()

    override fun start(filePath: String, sampleRateHz: Int, channelCount: Int) {
        this.filePath = filePath
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(
                MediaFormat.KEY_CHANNEL_MASK,
                if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            )
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        muxer = MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    override fun writeShortFrame(pcm: ShortArray, frameCount: Int) {
        val sampleCount = frameCount * channelCount
        val byteBuffer = ByteBuffer.allocate(sampleCount * 2)
        for (i in 0 until sampleCount) byteBuffer.putShort(pcm[i])
        val pcmBytes = byteBuffer.array()

        var offset = 0
        while (offset < pcmBytes.size) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: break
                inputBuffer.clear()
                val chunk = minOf(inputBuffer.capacity(), pcmBytes.size - offset)
                inputBuffer.put(pcmBytes, offset, chunk)
                codec.queueInputBuffer(inputIndex, 0, chunk, presentationTimeUs, 0)
                offset += chunk
                val framesInChunk = (chunk / 2) / channelCount
                presentationTimeUs += (framesInChunk * 1_000_000L) / sampleRateHz
            }
            drainEncoder(endOfStream = false)
        }
        totalFramesEncoded += frameCount
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
        loop@ while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break@loop
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                }
            }
        }
    }

    override fun bytesWritten(): Long = File(filePath).let { if (it.exists()) it.length() else 0L }

    override fun stop(): AudioFileWriterResult {
        drainEncoder(endOfStream = true)
        codec.stop()
        codec.release()
        if (muxerStarted) {
            muxer.stop()
        }
        muxer.release()
        val durationMs = (totalFramesEncoded * 1000L) / sampleRateHz
        val size = File(filePath).length()
        return AudioFileWriterResult(filePath, durationMs, size)
    }
}
