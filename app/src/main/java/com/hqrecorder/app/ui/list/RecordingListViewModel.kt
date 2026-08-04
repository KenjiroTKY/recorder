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
import com.hqrecorder.app.storage.SafStorageManager
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
}
