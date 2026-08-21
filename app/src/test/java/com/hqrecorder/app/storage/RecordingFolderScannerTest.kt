package com.hqrecorder.app.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/** SPEC.md 3.9 (issue #28): 保存先フォルダとの同期のうち、Android依存を持たない純粋なロジックの検証。 */
class RecordingFolderScannerTest {

    private fun metadata(id: String, fileUri: String, folderUri: String) = RecordingMetadata(
        id = id,
        displayName = id,
        fileUri = fileUri,
        folderUri = folderUri,
        createdAtEpochMs = 0L,
        durationMs = 0L,
        fileSizeBytes = 0L,
        formatType = "WAV",
        sampleRateHz = 44_100,
        bitDepth = 16,
        aacBitrateBps = 0,
        certificateStatus = "NONE"
    )

    @Test
    fun `flags recordings in scanned folders whose file was not found`() {
        val existing = listOf(
            metadata("rec1", "content://folderA/rec1.wav", "content://folderA"),
            metadata("rec2", "content://folderA/rec2.wav", "content://folderA")
        )

        val missing = RecordingFolderScanner.computeMissingIds(
            existing = existing,
            scannedFolderUris = setOf("content://folderA"),
            foundFileUris = setOf("content://folderA/rec1.wav")
        )

        assertEquals(setOf("rec2"), missing)
    }

    @Test
    fun `does not flag recordings in folders that were not scanned (inaccessible)`() {
        val existing = listOf(metadata("rec1", "content://folderB/rec1.wav", "content://folderB"))

        val missing = RecordingFolderScanner.computeMissingIds(
            existing = existing,
            scannedFolderUris = emptySet(),
            foundFileUris = emptySet()
        )

        assertEquals(emptySet<String>(), missing)
    }

    @Test
    fun `sidecarBaseName strips trailing part suffix`() {
        assertEquals(
            "20260802_120000_WAV48-24",
            RecordingFolderScanner.sidecarBaseName("20260802_120000_WAV48-24_part2")
        )
    }

    @Test
    fun `sidecarBaseName leaves names without part suffix unchanged`() {
        assertEquals(
            "20260802_120000_WAV48-24",
            RecordingFolderScanner.sidecarBaseName("20260802_120000_WAV48-24")
        )
    }
}
