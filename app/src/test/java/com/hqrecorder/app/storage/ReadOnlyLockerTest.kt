package com.hqrecorder.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadOnlyLockerTest {

    @Test
    fun `resolves path for primary volume document id`() {
        val path = ReadOnlyLocker.resolveLocalPath(
            authority = "com.android.externalstorage.documents",
            documentId = "primary:Music/HqRecorder/20260101_120000_WAV48-24.wav"
        )
        assertEquals("/storage/emulated/0/Music/HqRecorder/20260101_120000_WAV48-24.wav", path)
    }

    @Test
    fun `resolves path for non-primary sd card volume`() {
        val path = ReadOnlyLocker.resolveLocalPath(
            authority = "com.android.externalstorage.documents",
            documentId = "1234-5678:Recordings/foo.wav"
        )
        assertEquals("/storage/1234-5678/Recordings/foo.wav", path)
    }

    @Test
    fun `returns null for unsupported authority`() {
        val path = ReadOnlyLocker.resolveLocalPath(
            authority = "com.google.android.apps.docs.storage",
            documentId = "primary:Recordings/foo.wav"
        )
        assertNull(path)
    }

    @Test
    fun `returns null for malformed document id without colon`() {
        val path = ReadOnlyLocker.resolveLocalPath(
            authority = "com.android.externalstorage.documents",
            documentId = "malformed-document-id"
        )
        assertNull(path)
    }

    @Test
    fun `returns null when relative path is blank`() {
        val path = ReadOnlyLocker.resolveLocalPath(
            authority = "com.android.externalstorage.documents",
            documentId = "primary:"
        )
        assertNull(path)
    }
}
