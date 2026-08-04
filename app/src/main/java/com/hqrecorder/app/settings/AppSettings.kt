package com.hqrecorder.app.settings

import com.hqrecorder.app.audio.AudioQuality

data class AppSettings(
    val quality: AudioQuality = AudioQuality.STANDARD_WAV,
    val saveFolderUri: String? = null,
    val certificateEnabled: Boolean = false,
    val tsaUrl: String = DEFAULT_TSA_URL,
    val tsaAuthHeader: String? = null,
    val audioFocusPolicy: AudioFocusPolicy = AudioFocusPolicy.PAUSE,
    val trustedRootCaPems: List<String> = emptyList()
) {
    companion object {
        // FreeTSA.org: 認証不要・無料で使えるRFC3161準拠TSA。開発・検証用のデフォルト値
        // (SPEC.md 3.5参照)。法的証拠能力を重視する本番用途では有償の認定TSAへの切替を推奨。
        const val DEFAULT_TSA_URL = "https://freetsa.org/tsr"
    }
}
