package com.hqrecorder.app.ui.list

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hqrecorder.app.HqRecorderApp
import com.hqrecorder.app.certificate.CertificateVerifier
import com.hqrecorder.app.certificate.VerificationResult
import com.hqrecorder.app.certificate.custody.CustodyAction
import com.hqrecorder.app.storage.RecordingMetadata
import com.hqrecorder.app.storage.SafStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaybackUiState(
    val playingId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

class RecordingListViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<HqRecorderApp>()
    private val repo = app.container.recordingRepository
    private val settingsRepository = app.container.settingsRepository
    private val certificateManager = app.container.certificateManager
    private val custodyLogManager = app.container.custodyLogManager
    private val verifier = CertificateVerifier(application)

    val recordings: StateFlow<List<RecordingMetadata>> = repo.recordings

    private val _missingIds = MutableStateFlow<Set<String>>(emptySet())
    val missingIds: StateFlow<Set<String>> = _missingIds.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    init {
        syncWithFolders()
    }

    /**
     * 保存先フォルダ(現在の保存先および過去にインデックスで参照されたフォルダ)をSAF経由で走査し、
     * 一覧をフォルダの実態と同期する(SPEC.md 3.9)。一覧画面の表示時・手動更新時に呼び出す。
     */
    fun syncWithFolders() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        viewModelScope.launch {
            val missing = withContext(Dispatchers.IO) {
                val currentFolder = settingsRepository.settingsFlow.first().saveFolderUri
                val folderUris = (repo.recordings.value.map { it.folderUri } + listOfNotNull(currentFolder)).toSet()
                repo.syncWithFolders(folderUris)
            }
            _missingIds.value = missing
            _isSyncing.value = false
        }
    }

    private val _verificationResult = MutableStateFlow<VerificationResult?>(null)
    val verificationResult: StateFlow<VerificationResult?> = _verificationResult.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var positionTickerJob: Job? = null

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    /** 同じ録音を再生中/一時停止中ならresume、それ以外は他の再生を止めて新規再生を開始する(排他制御)。 */
    fun playOrResume(recording: RecordingMetadata) {
        if (_playbackState.value.playingId == recording.id && mediaPlayer != null) {
            resume()
            return
        }
        stopPlayback()
        val player = MediaPlayer()
        try {
            player.setDataSource(app, Uri.parse(recording.fileUri))
            player.setOnPreparedListener {
                it.start()
                _playbackState.value = PlaybackUiState(
                    playingId = recording.id,
                    isPlaying = true,
                    positionMs = 0L,
                    durationMs = it.duration.toLong().coerceAtLeast(0L)
                )
                startPositionTicker()
            }
            player.setOnCompletionListener { stopPlayback() }
            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            player.release()
            _playbackState.value = PlaybackUiState()
        }
    }

    fun pause() {
        mediaPlayer?.let { if (it.isPlaying) it.pause() }
        positionTickerJob?.cancel()
        _playbackState.value = _playbackState.value.copy(isPlaying = false)
    }

    fun resume() {
        val player = mediaPlayer ?: return
        player.start()
        _playbackState.value = _playbackState.value.copy(isPlaying = true)
        startPositionTicker()
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.seekTo(positionMs.toInt())
        _playbackState.value = _playbackState.value.copy(positionMs = positionMs)
    }

    fun stopPlayback() {
        positionTickerJob?.cancel()
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        mediaPlayer = null
        _playbackState.value = PlaybackUiState()
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = viewModelScope.launch {
            while (_playbackState.value.isPlaying) {
                val player = mediaPlayer ?: break
                _playbackState.value = _playbackState.value.copy(positionMs = player.currentPosition.toLong())
                delay(200)
            }
        }
    }

    private val _deletingId = MutableStateFlow<String?>(null)
    val deletingId: StateFlow<String?> = _deletingId.asStateFlow()

    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    fun retryCertificate(recording: RecordingMetadata) {
        viewModelScope.launch {
            val current = settingsRepository.settingsFlow.first()
            if (current.tsaUrl.isNotBlank()) {
                certificateManager.issueCertificate(recording, current.tsaUrl, current.tsaAuthHeader)
            }
        }
    }

    fun verify(recording: RecordingMetadata) {
        val certUri = recording.certificateFileUri ?: return
        viewModelScope.launch {
            val trustedRootCaPems = settingsRepository.settingsFlow.first().trustedRootCaPems
            val result = withContext(Dispatchers.IO) {
                val fileResult = verifier.verify(Uri.parse(recording.fileUri), Uri.parse(certUri), trustedRootCaPems)
                val startCertUri = recording.startCertificateFileUri
                var verifyResult = if (fileResult is VerificationResult.Valid && startCertUri != null) {
                    verifier.verifyStartEndOrder(Uri.parse(startCertUri), Uri.parse(certUri))
                } else {
                    fileResult
                }

                val signatureUri = recording.signatureFileUri
                val publicKeyUri = recording.publicKeyFileUri
                if (verifyResult is VerificationResult.Valid && signatureUri != null && publicKeyUri != null) {
                    val signatureValid = verifier.verifyDeviceSignature(
                        Uri.parse(recording.fileUri), Uri.parse(signatureUri), Uri.parse(publicKeyUri)
                    )
                    if (!signatureValid) {
                        verifyResult = VerificationResult.Invalid("端末鍵署名の検証に失敗しました（改ざんの可能性があります）")
                    }
                }

                custodyLogManager.append(CustodyAction.VERIFIED, recording.id, System.currentTimeMillis())
                verifyResult
            }
            _verificationResult.value = result
        }
    }

    fun clearVerificationResult() {
        _verificationResult.value = null
    }

    /** 録音本体・関連サイドカーファイルの削除を実行する(SPEC.md 3.7 / DESIGN.md 4.8)。 */
    fun deleteRecording(recording: RecordingMetadata) {
        if (_deletingId.value != null) return
        if (_playbackState.value.playingId == recording.id) stopPlayback()
        _deletingId.value = recording.id
        viewModelScope.launch {
            val succeeded = withContext(Dispatchers.IO) {
                SafStorageManager.deleteRecordingFiles(app, recording)
            }
            if (succeeded) {
                repo.removeRecording(recording.id)
                withContext(Dispatchers.IO) {
                    custodyLogManager.append(CustodyAction.DELETED, recording.id, System.currentTimeMillis())
                }
            } else {
                _deleteError.value = recording.displayName
            }
            _deletingId.value = null
        }
    }

    fun clearDeleteError() {
        _deleteError.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }
}
