package com.hqrecorder.app.audio

import kotlin.math.pow

/**
 * 録音感度（ゲイン）をPCMサンプルへ適用する純粋ロジック（SPEC.md 3.8, DESIGN.md 4.9）。
 * AudioRecordの再初期化を伴わないため、録音中でもリアルタイムに変更を反映できる。
 */
object GainProcessor {
    const val MIN_GAIN_DB = -24f
    /** CAMCORDER等、AGCで既に音量調整済みのソースを想定した標準上限。 */
    const val MAX_GAIN_DB = 24f
    /** UNPROCESSED（証拠性優先、AGC非適用）はベースの信号が小さいため上限を拡大する。 */
    const val MAX_GAIN_DB_UNPROCESSED = 40f
    const val DEFAULT_GAIN_DB = 0f

    /** クリッピング検出時の自動ゲイン低減オプション（SPEC.md 3.8, DESIGN.md 4.9）で1回に下げる量。 */
    const val AUTO_REDUCTION_STEP_DB = 3f
    /** 連続したクリッピングに対する過剰な追従を避けるための最小間隔。 */
    const val AUTO_REDUCTION_COOLDOWN_MS = 500L

    /** 電子証明書付与時(証拠性優先)はUNPROCESSED向けの拡大上限を使う（SPEC.md 3.1/3.8参照）。 */
    fun maxGainDb(preferUnprocessed: Boolean): Float =
        if (preferUnprocessed) MAX_GAIN_DB_UNPROCESSED else MAX_GAIN_DB

    private const val SHORT_MIN = Short.MIN_VALUE.toFloat()
    private const val SHORT_MAX = Short.MAX_VALUE.toFloat()

    fun clampGainDb(gainDb: Float, maxGainDb: Float = MAX_GAIN_DB_UNPROCESSED): Float =
        gainDb.coerceIn(MIN_GAIN_DB, maxGainDb)

    fun linearFactor(gainDb: Float): Float = 10f.pow(gainDb / 20f)

    /**
     * クリッピング検出時の自動ゲイン低減オプション用の純粋関数。クリップしていなければ現在値をそのまま返し、
     * クリップしていれば[stepDb]だけ下げた値を[MIN_GAIN_DB]でクランプして返す（自動で上げ直すことはしない）。
     */
    fun autoReduceGainDb(currentGainDb: Float, clipped: Boolean, stepDb: Float = AUTO_REDUCTION_STEP_DB): Float =
        if (clipped) (currentGainDb - stepDb).coerceAtLeast(MIN_GAIN_DB) else currentGainDb

    /** @return クリッピング（表現範囲超過によるハードクリップ）が発生した場合true */
    fun applyGainShort(buffer: ShortArray, length: Int, gainDb: Float): Boolean {
        if (gainDb == 0f) return false
        val factor = linearFactor(gainDb)
        var clipped = false
        for (i in 0 until length) {
            val amplified = buffer[i] * factor
            val clamped = amplified.coerceIn(SHORT_MIN, SHORT_MAX)
            if (clamped != amplified) clipped = true
            buffer[i] = clamped.toInt().toShort()
        }
        return clipped
    }

    /** @return クリッピング（表現範囲超過によるハードクリップ）が発生した場合true */
    fun applyGainFloat(buffer: FloatArray, length: Int, gainDb: Float): Boolean {
        if (gainDb == 0f) return false
        val factor = linearFactor(gainDb)
        var clipped = false
        for (i in 0 until length) {
            val amplified = buffer[i] * factor
            val clamped = amplified.coerceIn(-1f, 1f)
            if (clamped != amplified) clipped = true
            buffer[i] = clamped
        }
        return clipped
    }
}
