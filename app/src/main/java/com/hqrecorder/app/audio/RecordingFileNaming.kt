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

    private val wavTagRegex = Regex("WAV(\\d+)-(\\d+)")
    private val aacTagRegex = Regex("AAC(\\d+)")

    /**
     * baseName()の逆変換。保存先フォルダ走査でインデックス外のファイルを取り込む際(SPEC.md 3.9)、
     * ファイル名に含まれる品質タグをベストエフォートで推測する。命名規則に一致しない場合はnull(不明)。
     */
    fun parseQualityFromFileName(fileNameWithoutExtension: String): ParsedFileQuality? {
        wavTagRegex.find(fileNameWithoutExtension)?.let { match ->
            val sampleRateKHz = match.groupValues[1].toIntOrNull() ?: return null
            val bitDepth = match.groupValues[2].toIntOrNull() ?: return null
            return ParsedFileQuality(AudioFormatType.WAV, sampleRateKHz * 1000, bitDepth, 0)
        }
        aacTagRegex.find(fileNameWithoutExtension)?.let { match ->
            val kbps = match.groupValues[1].toIntOrNull() ?: return null
            return ParsedFileQuality(AudioFormatType.AAC, 0, 0, kbps * 1000)
        }
        return null
    }
}

data class ParsedFileQuality(
    val formatType: AudioFormatType,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val aacBitrateBps: Int
)
