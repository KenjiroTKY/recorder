package com.hqrecorder.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Storage Access Framework経由でユーザーが選択した保存先フォルダへの
 * 永続権限付与・ファイル作成・書き込みをまとめるユーティリティ。
 */
object SafStorageManager {

    fun persistPermission(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    fun createFileInFolder(context: Context, folderUri: Uri, displayName: String, mimeType: String): Uri? {
        val dir = DocumentFile.fromTreeUri(context, folderUri) ?: return null
        return dir.createFile(mimeType, displayName)?.uri
    }

    fun copyLocalFileIntoUri(context: Context, localFile: File, destUri: Uri) {
        val out = context.contentResolver.openOutputStream(destUri)
            ?: throw IllegalStateException("保存先に書き込めません: $destUri")
        out.use { output ->
            localFile.inputStream().use { input -> input.copyTo(output) }
        }
    }

    fun writeSidecarNextToFile(
        context: Context,
        folderUri: Uri,
        sidecarName: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream"
    ): Uri {
        val dest = createFileInFolder(context, folderUri, sidecarName, mimeType)
            ?: throw IllegalStateException("証明書ファイルを作成できませんでした")
        val out = context.contentResolver.openOutputStream(dest)
            ?: throw IllegalStateException("証明書ファイルに書き込めませんでした")
        out.use { it.write(bytes) }
        return dest
    }

    /**
     * 保存後の読み取り専用化(9.6)。ExternalStorageProvider経由のドキュメントに限り
     * 実ファイルパスを解決し書き込み属性を落とす。それ以外のプロバイダは非対応として扱う。
     */
    fun tryMakeReadOnly(fileUri: Uri): ReadOnlyStatus {
        val authority = fileUri.authority ?: return ReadOnlyStatus.UNSUPPORTED
        val documentId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull()
            ?: return ReadOnlyStatus.UNSUPPORTED
        val localPath = ReadOnlyLocker.resolveLocalPath(authority, documentId)
            ?: return ReadOnlyStatus.UNSUPPORTED
        return try {
            val file = File(localPath)
            if (file.exists() && file.setWritable(false, false)) {
                ReadOnlyStatus.APPLIED
            } else {
                ReadOnlyStatus.FAILED
            }
        } catch (e: Exception) {
            ReadOnlyStatus.FAILED
        }
    }

    /**
     * 録音の音声本体と関連サイドカーファイル（電子証明書 `.tsr`、区間ハッシュチェーン `.chain.json`）を
     * まとめて削除する(SPEC.md 3.7 / DESIGN.md 4.8)。存在しないファイルはスキップする。
     * 1件でも削除に失敗した場合はfalseを返し、呼び出し側はメタデータを消さずに再試行可能とする。
     */
    fun deleteRecordingFiles(context: Context, recording: RecordingMetadata): Boolean {
        val targets = mutableListOf(Uri.parse(recording.fileUri))
        recording.certificateFileUri?.let { targets.add(Uri.parse(it)) }

        val audioFileName = DocumentFile.fromSingleUri(context, Uri.parse(recording.fileUri))?.name
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(recording.folderUri))
        val chainDoc = audioFileName?.let { folder?.findFile("$it.chain.json") }

        var allSucceeded = true
        for (uri in targets) {
            val doc = DocumentFile.fromSingleUri(context, uri)
            if (doc != null && doc.exists() && !doc.delete()) {
                allSucceeded = false
            }
        }
        if (chainDoc != null && chainDoc.exists() && !chainDoc.delete()) {
            allSucceeded = false
        }
        return allSucceeded
    }
}
