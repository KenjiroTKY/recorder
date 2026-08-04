package com.hqrecorder.app.ui.list

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hqrecorder.app.HqRecorderApp
import com.hqrecorder.app.certificate.CertificateVerifier
import com.hqrecorder.app.certificate.VerificationResult
import com.hqrecorder.app.certificate.custody.CustodyAction
import com.hqrecorder.app.storage.RecordingMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                val verifyResult = verifier.verify(Uri.parse(recording.fileUri), Uri.parse(certUri), trustedRootCaPems)
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
