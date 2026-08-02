package com.hqrecorder.app.service

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.hqrecorder.app.HqRecorderApp
import com.hqrecorder.app.audio.AudioFileWriterResult
import com.hqrecorder.app.audio.AudioFormatType
import com.hqrecorder.app.audio.AudioLevel
import com.hqrecorder.app.audio.AudioQuality
import com.hqrecorder.app.audio.RecorderListener
import com.hqrecorder.app.audio.RecordingState
import com.hqrecorder.app.audio.StereoAudioRecorder
import com.hqrecorder.app.storage.CertificateStatus
import com.hqrecorder.app.storage.RecordingMetadata
import com.hqrecorder.app.storage.SafStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class RecordingUiState(
    val state: RecordingState = RecordingState.IDLE,
    val elapsedMs: Long = 0L,
    val level: AudioLevel = AudioLevel(0f, 0f),
    val isStereo: Boolean = true,
    val currentFileName: String = "",
    val errorMessage: String? = null
)

class RecordingService : Service(), RecorderListener {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var recorder: StereoAudioRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var startElapsedRealtime = 0L
    private var accumulatedPauseMs = 0L
    private var pauseStartedAt = 0L

    private var currentQuality: AudioQuality = AudioQuality.STANDARD_WAV
    private var currentFolderUri: Uri? = null
    private var currentBaseName: String = ""
    private var currentRecordingId: String = ""
    private val partResults = mutableListOf<AudioFileWriterResult>()

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private lateinit var notificationHelper: NotificationHelper

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    fun startRecording(quality: AudioQuality, folderUri: Uri) {
        if (_uiState.value.state == RecordingState.RECORDING) return

        currentQuality = quality
        currentFolderUri = folderUri
        currentRecordingId = UUID.randomUUID().toString()
        val timeTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        currentBaseName = "${timeTag}_${quality.qualityTag}"
        partResults.clear()
        accumulatedPauseMs = 0L

        startForeground(NOTIFICATION_ID, notificationHelper.buildNotification(RecordingState.RECORDING, 0L))
        acquireWakeLock()

        recorder = StereoAudioRecorder(listener = this).also {
            it.start(quality, workDir = cacheWorkDir(), baseFileName = currentBaseName)
        }
        startElapsedRealtime = SystemClock.elapsedRealtime()

        _uiState.value = RecordingUiState(
            state = RecordingState.RECORDING,
            isStereo = recorder?.isStereo ?: true,
            currentFileName = "$currentBaseName.${quality.fileExtension}"
        )
        startTicker()
    }

    private fun startTicker() {
        serviceScope.launch {
            while (_uiState.value.state == RecordingState.RECORDING || _uiState.value.state == RecordingState.PAUSED) {
                if (_uiState.value.state == RecordingState.RECORDING) {
                    val elapsed = SystemClock.elapsedRealtime() - startElapsedRealtime - accumulatedPauseMs
                    _uiState.value = _uiState.value.copy(elapsedMs = elapsed)
                    notificationHelper.notify(
                        NOTIFICATION_ID,
                        notificationHelper.buildNotification(RecordingState.RECORDING, elapsed)
                    )
                }
                delay(500)
            }
        }
    }

    fun pauseRecording() {
        if (_uiState.value.state != RecordingState.RECORDING) return
        recorder?.pause()
        pauseStartedAt = SystemClock.elapsedRealtime()
        _uiState.value = _uiState.value.copy(state = RecordingState.PAUSED)
        notificationHelper.notify(
            NOTIFICATION_ID,
            notificationHelper.buildNotification(RecordingState.PAUSED, _uiState.value.elapsedMs)
        )
    }

    fun resumeRecording() {
        if (_uiState.value.state != RecordingState.PAUSED) return
        accumulatedPauseMs += SystemClock.elapsedRealtime() - pauseStartedAt
        recorder?.resume()
        _uiState.value = _uiState.value.copy(state = RecordingState.RECORDING)
    }

    fun stopRecording() {
        if (_uiState.value.state == RecordingState.IDLE) return
        _uiState.value = _uiState.value.copy(state = RecordingState.STOPPING)
        recorder?.stop()
        recorder = null
        releaseWakeLock()
        finalizeRecording()
        _uiState.value = RecordingUiState(state = RecordingState.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finalizeRecording() {
        val folderUri = currentFolderUri ?: return
        val app = application as HqRecorderApp
        val repo = app.container.recordingRepository

        val destUris = mutableListOf<Uri>()
        var totalSize = 0L
        var totalDuration = 0L

        for (part in partResults) {
            val localFile = File(part.filePath)
            val mime = if (currentQuality.formatType == AudioFormatType.WAV) "audio/wav" else "audio/mp4"
            runCatching {
                val destUri = SafStorageManager.createFileInFolder(this, folderUri, localFile.name, mime)
                    ?: throw IllegalStateException("保存先にファイルを作成できません")
                SafStorageManager.copyLocalFileIntoUri(this, localFile, destUri)
                destUris.add(destUri)
                totalSize += part.fileSizeBytes
                totalDuration += part.durationMs
            }.onFailure {
                _uiState.value = _uiState.value.copy(errorMessage = "保存に失敗しました: ${it.message}")
            }
            localFile.delete()
        }

        if (destUris.isEmpty()) return

        val metadata = RecordingMetadata(
            id = currentRecordingId,
            displayName = currentBaseName,
            fileUri = destUris.first().toString(),
            folderUri = folderUri.toString(),
            createdAtEpochMs = System.currentTimeMillis(),
            durationMs = totalDuration,
            fileSizeBytes = totalSize,
            formatType = currentQuality.formatType.name,
            sampleRateHz = currentQuality.sampleRateHz,
            bitDepth = currentQuality.bitDepth,
            aacBitrateBps = currentQuality.aacBitrateBps,
            certificateStatus = CertificateStatus.NONE.name
        )
        repo.addRecording(metadata)

        serviceScope.launch {
            val settings = app.container.settingsRepository.settingsFlow.first()
            if (settings.certificateEnabled && settings.tsaUrl.isNotBlank()) {
                val pending = metadata.copy(certificateStatus = CertificateStatus.PENDING.name)
                repo.updateRecording(pending)
                app.container.certificateManager.issueCertificate(
                    recording = pending,
                    tsaUrl = settings.tsaUrl,
                    authHeader = settings.tsaAuthHeader
                )
            }
        }
    }

    private fun cacheWorkDir(): File = File(cacheDir, "recording_tmp").apply { mkdirs() }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HqRecorder:RecordingWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onLevel(level: AudioLevel) {
        _uiState.value = _uiState.value.copy(level = level)
    }

    override fun onPartFinished(result: AudioFileWriterResult) {
        partResults.add(result)
    }

    override fun onError(error: Throwable) {
        _uiState.value = _uiState.value.copy(errorMessage = error.message)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_PAUSE = "com.hqrecorder.app.action.PAUSE"
        const val ACTION_RESUME = "com.hqrecorder.app.action.RESUME"
        const val ACTION_STOP = "com.hqrecorder.app.action.STOP"
    }
}
