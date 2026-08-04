package com.hqrecorder.app.certificate.custody

import com.hqrecorder.app.certificate.toHex
import java.security.MessageDigest

sealed class CustodyLogVerificationResult {
    object Valid : CustodyLogVerificationResult()
    object Empty : CustodyLogVerificationResult()
    data class Broken(val brokenAtIndex: Int) : CustodyLogVerificationResult()
}

/**
 * Chain of Custodyログの追記専用ハッシュチェーンを構築・検証する純粋ロジック(SPEC.md 3.6 / DESIGN.md 9.4)。
 * 各エントリのentryHashは自分以外の全フィールド＋prevEntryHashのSHA-256とし、
 * 改ざん時にはprevEntryHashの連鎖またはentryHash自体の再計算値が一致しなくなり検知できる。
 */
object CustodyLogChain {

    fun append(
        previous: CustodyLogEntry?,
        timestampEpochMs: Long,
        action: CustodyAction,
        actor: String,
        targetRecordingId: String
    ): CustodyLogEntry {
        val prevHash = previous?.entryHash
        val entryHash = computeEntryHash(timestampEpochMs, action.name, actor, targetRecordingId, prevHash)
        return CustodyLogEntry(
            timestampEpochMs = timestampEpochMs,
            action = action.name,
            actor = actor,
            targetRecordingId = targetRecordingId,
            prevEntryHash = prevHash,
            entryHash = entryHash
        )
    }

    /** prevEntryHashの連結とentryHashの再計算値を先頭から突き合わせ、最初に破綻したエントリのindexを返す。 */
    fun verify(entries: List<CustodyLogEntry>): CustodyLogVerificationResult {
        if (entries.isEmpty()) return CustodyLogVerificationResult.Empty
        var previousHash: String? = null
        for ((index, entry) in entries.withIndex()) {
            if (entry.prevEntryHash != previousHash) return CustodyLogVerificationResult.Broken(index)
            val recomputed = computeEntryHash(
                entry.timestampEpochMs, entry.action, entry.actor, entry.targetRecordingId, entry.prevEntryHash
            )
            if (recomputed != entry.entryHash) return CustodyLogVerificationResult.Broken(index)
            previousHash = entry.entryHash
        }
        return CustodyLogVerificationResult.Valid
    }

    private fun computeEntryHash(
        timestampEpochMs: Long,
        action: String,
        actor: String,
        targetRecordingId: String,
        prevEntryHash: String?
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val payload = listOf(
            timestampEpochMs.toString(),
            action,
            actor,
            targetRecordingId,
            prevEntryHash.orEmpty()
        ).joinToString("|")
        digest.update(payload.toByteArray(Charsets.UTF_8))
        return digest.digest().toHex()
    }
}
