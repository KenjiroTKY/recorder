package com.hqrecorder.app.audio

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SPEC.md 3.3の命名規則（`yyyyMMdd_HHmmss_<品質タグ>.拡張子`）と、
 * 長時間録音時のパート分割ファイル名（`_part2`, `_part3`...）を決定する純粋関数群。
 */
object RecordingFileNaming {

    private fun timestampFormat() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun baseName(date: Date, quality: AudioQuality): String {
        val timeTag = timestampFormat().format(date)
        return "${timeTag}_${quality.qualityTag}"
    }

    fun partFileName(baseFileName: String, extension: String, partIndex: Int): String {
        val suffix = if (partIndex <= 1) "" else "_part$partIndex"
        return "$baseFileName$suffix.$extension"
    }
}
