package com.hqrecorder.app.certificate.custody

import kotlinx.serialization.Serializable

@Serializable
data class CustodyLogEntry(
    val timestampEpochMs: Long,
    val action: String,
    val actor: String,
    val targetRecordingId: String,
    val prevEntryHash: String?,
    val entryHash: String
)
