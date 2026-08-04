package com.hqrecorder.app.storage

import kotlinx.serialization.Serializable

@Serializable
data class RecordingMetadata(
    val id: String,
    val displayName: String,
    val fileUri: String,
    val folderUri: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val formatType: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val aacBitrateBps: Int,
    val certificateStatus: String,
    val certificateFileUri: String? = null,
    val certificateIssuedAtEpochMs: Long? = null,
    val certificateTsaUrl: String? = null,
    val clockReliability: String? = null,
    val clockOffsetMs: Long? = null,
    val startCertificateFileUri: String? = null,
    val startCertificateIssuedAtEpochMs: Long? = null,
    val signatureFileUri: String? = null,
    val publicKeyFileUri: String? = null,
    val readOnlyStatus: String? = null
)
