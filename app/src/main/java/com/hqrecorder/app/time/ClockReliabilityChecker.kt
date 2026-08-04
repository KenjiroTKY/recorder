package com.hqrecorder.app.time

import kotlin.math.abs

/**
 * 録音開始時に主要NTPサーバとシステム時計の差分を確認し、時刻証明の信頼性判断材料とする(9.7)。
 * ネットワーク不通時はUNKNOWNとして扱い、録音自体は継続する(致命的エラーにはしない)。
 */
class ClockReliabilityChecker(
    private val sntpClient: SntpClient = SntpClient(),
    private val ntpHost: String = "time.google.com",
    private val thresholdMs: Long = DEFAULT_THRESHOLD_MS
) {
    data class CheckResult(val reliability: ClockReliability, val offsetMs: Long?)

    fun check(): CheckResult {
        val offsetMs = runCatching { sntpClient.query(ntpHost) }.getOrNull()
        return CheckResult(classify(offsetMs, thresholdMs), offsetMs)
    }

    companion object {
        const val DEFAULT_THRESHOLD_MS = 2000L

        /** Android/ネットワークに非依存の純粋な閾値判定ロジック。ユニットテスト対象。 */
        fun classify(offsetMs: Long?, thresholdMs: Long = DEFAULT_THRESHOLD_MS): ClockReliability = when {
            offsetMs == null -> ClockReliability.UNKNOWN
            abs(offsetMs) <= thresholdMs -> ClockReliability.RELIABLE
            else -> ClockReliability.UNVERIFIED
        }
    }
}
