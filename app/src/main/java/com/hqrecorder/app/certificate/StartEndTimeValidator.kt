package com.hqrecorder.app.certificate

import java.util.Date

/**
 * 開始時刻証明(9.1)と終了時刻証明のTSA発行時刻を突き合わせ、
 * 開始<終了の時間的整合性を判定する純粋ロジック。
 */
object StartEndTimeValidator {
    fun isValidOrder(startTime: Date, endTime: Date): Boolean = startTime.before(endTime)
}
