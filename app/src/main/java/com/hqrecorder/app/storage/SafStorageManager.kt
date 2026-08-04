package com.hqrecorder.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
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
}
