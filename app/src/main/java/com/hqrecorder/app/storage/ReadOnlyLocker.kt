package com.hqrecorder.app.storage

/**
 * SAFドキュメントURIから実ファイルパスを解決する純粋ロジック(9.6)。
 * DocumentsContractには汎用の「書き込みFlagを落とす」APIが存在しないため、
 * 確実にローカルファイルとして扱える com.android.externalstorage.documents
 * (内部ストレージ/SDカードをSAFで選択した場合の標準プロバイダ)のみ対応する。
 * それ以外のプロバイダ(authority)は読み取り専用化非対応として扱う。
 */
object ReadOnlyLocker {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_VOLUME_ROOT = "/storage/emulated/0"

    fun resolveLocalPath(authority: String, documentId: String): String? {
        if (authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val separatorIndex = documentId.indexOf(':')
        if (separatorIndex < 0) return null
        val volumeId = documentId.substring(0, separatorIndex)
        val relativePath = documentId.substring(separatorIndex + 1)
        if (relativePath.isBlank()) return null
        val base = if (volumeId == "primary") PRIMARY_VOLUME_ROOT else "/storage/$volumeId"
        return "$base/$relativePath"
    }
}
