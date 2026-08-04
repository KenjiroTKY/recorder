package com.hqrecorder.app.certificate.signing

import android.content.Context
import android.net.Uri
import com.hqrecorder.app.certificate.Sha256
import com.hqrecorder.app.storage.RecordingMetadata
import com.hqrecorder.app.storage.SafStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SigningResult(val signatureFileUri: String, val publicKeyFileUri: String)

/**
 * 録音確定時のファイルハッシュに端末鍵(Android Keystore)で署名し、
 * <ファイル名>.sig（署名）/ <ファイル名>.pub（検証用公開鍵）として保存先フォルダへ書き出す。
 */
class DeviceSigningManager(private val context: Context) {
    private val keyManager = DeviceKeyManager()

    suspend fun sign(recording: RecordingMetadata): SigningResult = withContext(Dispatchers.IO) {
        val fileHash = hashUri(Uri.parse(recording.fileUri))
        val signatureBytes = keyManager.sign(fileHash)
        val publicKeyBytes = keyManager.publicKey().encoded
        val folderUri = Uri.parse(recording.folderUri)

        val signatureUri = SafStorageManager.writeSidecarNextToFile(
            context = context,
            folderUri = folderUri,
            sidecarName = "${recording.displayName}.sig",
            bytes = signatureBytes,
            mimeType = "application/octet-stream"
        )
        val publicKeyUri = SafStorageManager.writeSidecarNextToFile(
            context = context,
            folderUri = folderUri,
            sidecarName = "${recording.displayName}.pub",
            bytes = publicKeyBytes,
            mimeType = "application/octet-stream"
        )

        SigningResult(signatureUri.toString(), publicKeyUri.toString())
    }

    private fun hashUri(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("録音ファイルを読み込めません: $uri")
        return Sha256.hash(input)
    }
}
