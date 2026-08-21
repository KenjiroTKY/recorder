package com.hqrecorder.app.storage

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.hqrecorder.app.audio.RecordingFileNaming
import java.util.UUID

/**
 * 保存先フォルダ(現在および過去に使用したフォルダ)の実ファイルを走査し、録音履歴インデックス
 * (RecordingRepository)に存在しないファイルをインポートする(SPEC.md 3.9 / issue #28)。
 */
object RecordingFolderScanner {

    private val AUDIO_EXTENSIONS = setOf("wav", "m4a")
    private val PART_SUFFIX_REGEX = Regex("_part\\d+$")

    data class ScanResult(
        val imported: List<RecordingMetadata>,
        val missingIds: Set<String>
    )

    /**
     * [folderUris]配下を走査する。アクセス不能なフォルダ(URI権限失効等)は走査自体をスキップし、
     * そのフォルダに紐づく既存インデックスには手を加えない。
     */
    fun scan(context: Context, existing: List<RecordingMetadata>, folderUris: Set<String>): ScanResult {
        val existingByFileUri = existing.associateBy { it.fileUri }
        val imported = mutableListOf<RecordingMetadata>()
        val foundFileUris = mutableSetOf<String>()
        val scannedFolderUris = mutableSetOf<String>()

        for (folderUriString in folderUris) {
            val folderUri = runCatching { Uri.parse(folderUriString) }.getOrNull() ?: continue
            val folder = runCatching { DocumentFile.fromTreeUri(context, folderUri) }.getOrNull()
            if (folder == null || !folder.exists() || !folder.canRead()) continue
            scannedFolderUris += folderUriString

            val files = runCatching { folder.listFiles() }.getOrDefault(emptyArray()).filterNotNull()
            val filesByName = files.associateBy { it.name }

            for (doc in files) {
                if (!doc.isFile) continue
                val name = doc.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in AUDIO_EXTENSIONS) continue

                val fileUriString = doc.uri.toString()
                foundFileUris += fileUriString
                if (existingByFileUri.containsKey(fileUriString)) continue

                imported += buildMetadata(context, doc, folderUri, name, ext, filesByName)
            }
        }

        return ScanResult(imported, computeMissingIds(existing, scannedFolderUris, foundFileUris))
    }

    /** 走査済みフォルダ配下にありながら実ファイルが見つからなかった既存録音のIDを返す(SPEC.md 3.9)。 */
    fun computeMissingIds(
        existing: List<RecordingMetadata>,
        scannedFolderUris: Set<String>,
        foundFileUris: Set<String>
    ): Set<String> = existing.asSequence()
        .filter { it.folderUri in scannedFolderUris }
        .filter { it.fileUri !in foundFileUris }
        .map { it.id }
        .toSet()

    /**
     * サイドカーファイル(`.tsr`等)は録音全体の表示名(パート分割前の共通ベース名)を基準に
     * 命名されるため、`_partN`サフィックスを取り除いて紐付け候補名を求める(SPEC.md 3.9)。
     */
    fun sidecarBaseName(fileNameWithoutExtension: String): String =
        fileNameWithoutExtension.replace(PART_SUFFIX_REGEX, "")

    private fun buildMetadata(
        context: Context,
        doc: DocumentFile,
        folderUri: Uri,
        fileName: String,
        extension: String,
        siblingsByName: Map<String?, DocumentFile>
    ): RecordingMetadata {
        val nameWithoutExt = fileName.removeSuffix(".$extension")
        val quality = RecordingFileNaming.parseQualityFromFileName(nameWithoutExt)
        val certDoc = siblingsByName["${sidecarBaseName(nameWithoutExt)}.tsr"]

        return RecordingMetadata(
            id = UUID.randomUUID().toString(),
            displayName = nameWithoutExt,
            fileUri = doc.uri.toString(),
            folderUri = folderUri.toString(),
            createdAtEpochMs = doc.lastModified(),
            durationMs = readDurationMs(context, doc.uri),
            fileSizeBytes = doc.length(),
            formatType = quality?.formatType?.name ?: "UNKNOWN",
            sampleRateHz = quality?.sampleRateHz ?: 0,
            bitDepth = quality?.bitDepth ?: 0,
            aacBitrateBps = quality?.aacBitrateBps ?: 0,
            certificateStatus = if (certDoc != null) CertificateStatus.ISSUED.name else CertificateStatus.NONE.name,
            certificateFileUri = certDoc?.uri?.toString()
        )
    }

    private fun readDurationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }
}
