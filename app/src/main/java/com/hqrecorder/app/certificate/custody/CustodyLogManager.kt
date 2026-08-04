package com.hqrecorder.app.certificate.custody

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Chain of Custodyログをapp内部ストレージへ追記専用(JSON Lines)で永続化する(SPEC.md 3.6 / DESIGN.md 9.4)。
 * actorは端末のInstallation ID相当として自前でUUIDを生成しSharedPreferencesへ保持する
 * （マルチユーザー識別は将来検討、DESIGN.md 9.4節参照）。
 */
class CustodyLogManager(private val context: Context) {

    private val logFile: File get() = File(context.filesDir, "custody_log.jsonl")
    private val json = Json { ignoreUnknownKeys = true }

    val installationId: String by lazy { loadOrCreateInstallationId() }

    @Synchronized
    fun append(action: CustodyAction, targetRecordingId: String, nowEpochMs: Long): CustodyLogEntry {
        val previous = readAll().lastOrNull()
        val entry = CustodyLogChain.append(
            previous = previous,
            timestampEpochMs = nowEpochMs,
            action = action,
            actor = installationId,
            targetRecordingId = targetRecordingId
        )
        logFile.appendText(json.encodeToString(entry) + "\n")
        return entry
    }

    fun readAll(): List<CustodyLogEntry> {
        if (!logFile.exists()) return emptyList()
        return logFile.readLines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<CustodyLogEntry>(it) }
    }

    fun verifyLog(): CustodyLogVerificationResult = CustodyLogChain.verify(readAll())

    private fun loadOrCreateInstallationId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_INSTALLATION_ID, null)
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALLATION_ID, generated).apply()
        return generated
    }

    companion object {
        private const val PREFS_NAME = "hq_recorder_device"
        private const val KEY_INSTALLATION_ID = "installation_id"
    }
}
