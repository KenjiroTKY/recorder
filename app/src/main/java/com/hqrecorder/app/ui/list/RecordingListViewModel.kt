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

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }

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
            val result = withContext(Dispatchers.IO) {
                val verifyResult = verifier.verify(Uri.parse(recording.fileUri), Uri.parse(certUri))
                custodyLogManager.append(CustodyAction.VERIFIED, recording.id, System.currentTimeMillis())
                verifyResult
            }
            _verificationResult.value = result
        }
    }

    fun clearVerificationResult() {
        _verificationResult.value = null
    }
}
