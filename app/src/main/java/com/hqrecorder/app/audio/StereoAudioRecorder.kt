package com.hqrecorder.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

interface RecorderListener {
    fun onLevel(level: AudioLevel) {}
    fun onPartFinished(result: AudioFileWriterResult) {}
    fun onError(error: Throwable) {}
}

/**
 * AudioRecordでステレオPCMを取得し、AudioFileWriterへ流し込む録音エンジン本体。
 * 対応端末が乏しい場合はモノラル/16bitへ自動フォールバックする。
 */
class StereoAudioRecorder(
    private val listener: RecorderListener,
    private val maxPartBytes: Long = 1_800_000_000L // WAVヘッダの32bitサイズ上限(4GB)対策
) {
    private var audioRecord: AudioRecord? = null
    private var readThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    private var writer: AudioFileWriter? = null
    private var effectiveChannelCount = 2
    private var effectiveEncoding = AudioFormat.ENCODING_PCM_16BIT
    private var quality: AudioQuality = AudioQuality.STANDARD_WAV
    private var workDir: File? = null
    private var partIndex = 1
    private var baseFileName = "recording"

    val isStereo: Boolean get() = effectiveChannelCount == 2

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(quality: AudioQuality, workDir: File, baseFileName: String) {
        this.quality = quality
        this.workDir = workDir
        this.baseFileName = baseFileName
        this.partIndex = 1

        val sampleRate = quality.sampleRateHz
        val wantFloat = quality.formatType == AudioFormatType.WAV && quality.bitDepth >= 24

        val (record, channelCount, encoding) = createAudioRecord(sampleRate, wantFloat)
        audioRecord = record
        effectiveChannelCount = channelCount
        effectiveEncoding = encoding

        writer = createWriter(quality, effectiveEncoding)
        openNewPart()

        record.startRecording()
        running.set(true)
        paused.set(false)

        readThread = Thread(::readLoop, "StereoAudioRecorder-Read").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun pause() { paused.set(true) }

    fun resume() { paused.set(false) }

    fun stop() {
        running.set(false)
        readThread?.join(2_000)
        readThread = null
        audioRecord?.let { record ->
            runCatching { record.stop() }
            record.release()
        }
        audioRecord = null
        closeCurrentPart()
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(sampleRate: Int, wantFloat: Boolean): Triple<AudioRecord, Int, Int> {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.MIC
        )
        val channelConfigs = intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
        val encodings = if (wantFloat) {
            intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
        } else {
            intArrayOf(AudioFormat.ENCODING_PCM_16BIT)
        }

        for (source in sources) {
            for (channelConfig in channelConfigs) {
                for (encoding in encodings) {
                    val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
                    if (minBuf <= 0) continue
                    val bufferSize = minBuf * 4
                    val record = try {
                        AudioRecord(source, sampleRate, channelConfig, encoding, bufferSize)
                    } catch (e: Exception) {
                        null
                    }
                    if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                        val channelCount = if (channelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                        return Triple(record, channelCount, encoding)
                    }
                    record?.release()
                }
            }
        }
        throw IllegalStateException("録音デバイスを初期化できませんでした")
    }

    private fun createWriter(quality: AudioQuality, encoding: Int): AudioFileWriter {
        return when (quality.formatType) {
            AudioFormatType.WAV -> WavFileWriter(
                bitDepth = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) quality.bitDepth else 16
            )
            AudioFormatType.AAC -> AacFileWriter(bitrateBps = quality.aacBitrateBps)
        }
    }

    private fun openNewPart() {
        val dir = workDir ?: return
        val suffix = if (partIndex == 1) "" else "_part$partIndex"
        val file = File(dir, "$baseFileName$suffix.${quality.fileExtension}")
        writer?.start(file.absolutePath, quality.sampleRateHz, effectiveChannelCount)
    }

    private fun closeCurrentPart() {
        writer?.let { w ->
            val result = w.stop()
            listener.onPartFinished(result)
        }
    }

    private fun readLoop() {
        val record = audioRecord ?: return
        val frameCapacity = 2048
        var lastLevelEmit = 0L

        try {
            if (effectiveEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val buffer = FloatArray(frameCapacity * effectiveChannelCount)
                while (running.get()) {
                    if (paused.get()) { Thread.sleep(20); continue }
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        val frames = read / effectiveChannelCount
                        writer?.writeFloatFrame(buffer, frames)
                        maybeRotate()
                        lastLevelEmit = maybeEmitLevel(lastLevelEmit) { computeLevelFloat(buffer, read) }
                    }
                }
            } else {
                val buffer = ShortArray(frameCapacity * effectiveChannelCount)
                while (running.get()) {
                    if (paused.get()) { Thread.sleep(20); continue }
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        val frames = read / effectiveChannelCount
                        writer?.writeShortFrame(buffer, frames)
                        maybeRotate()
                        lastLevelEmit = maybeEmitLevel(lastLevelEmit) { computeLevelShort(buffer, read) }
                    }
                }
            }
        } catch (e: Exception) {
            listener.onError(e)
        }
    }

    private inline fun maybeEmitLevel(lastEmit: Long, compute: () -> AudioLevel): Long {
        val now = System.currentTimeMillis()
        if (now - lastEmit > 100) {
            listener.onLevel(compute())
            return now
        }
        return lastEmit
    }

    private fun maybeRotate() {
        val w = writer ?: return
        if (w.bytesWritten() >= maxPartBytes) {
            closeCurrentPart()
            partIndex += 1
            openNewPart()
        }
    }

    private fun computeLevelShort(buffer: ShortArray, length: Int): AudioLevel {
        var left = 0
        var right = 0
        var i = 0
        while (i < length) {
            left = max(left, abs(buffer[i].toInt()))
            if (effectiveChannelCount == 2 && i + 1 < length) {
                right = max(right, abs(buffer[i + 1].toInt()))
            }
            i += effectiveChannelCount
        }
        val maxAmp = 32767f
        return AudioLevel(left / maxAmp, (if (effectiveChannelCount == 2) right else left) / maxAmp)
    }

    private fun computeLevelFloat(buffer: FloatArray, length: Int): AudioLevel {
        var left = 0f
        var right = 0f
        var i = 0
        while (i < length) {
            left = max(left, abs(buffer[i]))
            if (effectiveChannelCount == 2 && i + 1 < length) {
                right = max(right, abs(buffer[i + 1]))
            }
            i += effectiveChannelCount
        }
        return AudioLevel(left, if (effectiveChannelCount == 2) right else left)
    }
}
