package com.hqrecorder.app.certificate

import android.content.Context
import android.net.Uri
import com.hqrecorder.app.storage.CertificateStatus
import com.hqrecorder.app.storage.RecordingMetadata
import com.hqrecorder.app.storage.RecordingRepository
import com.hqrecorder.app.storage.SafStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * 録音ファイルに対するRFC3161タイムスタンプ証明書の発行を統括する。
 * 発行結果(成功/失敗)はRecordingRepositoryへ書き戻し、一覧画面から状態確認・再試行できるようにする。
 */
class CertificateManager(
    private val context: Context,
    private val repository: RecordingRepository
) {
    suspend fun issueCertificate(
        recording: RecordingMetadata,
        tsaUrl: String,
        authHeader: String?
    ): Result<RecordingMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val fileHash = hashUri(Uri.parse(recording.fileUri))
            val client = TimestampClient(tsaUrl, authHeader)
            val tokenBytes = client.requestTimestamp(fileHash)

            val sidecarName = "${recording.displayName}.tsr"
            val sidecarUri = SafStorageManager.writeSidecarNextToFile(
                context = context,
                folderUri = Uri.parse(recording.folderUri),
                sidecarName = sidecarName,
                bytes = tokenBytes
            )

            val updated = recording.copy(
                certificateStatus = CertificateStatus.ISSUED.name,
                certificateFileUri = sidecarUri.toString(),
                certificateIssuedAtEpochMs = System.currentTimeMillis(),
                certificateTsaUrl = tsaUrl
            )
            repository.updateRecording(updated)
            updated
        }.onFailure {
            repository.updateRecording(recording.copy(certificateStatus = CertificateStatus.FAILED.name))
        }
    }

    private fun hashUri(uri: Uri): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("録音ファイルを読み込めません: $uri")
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }
}
