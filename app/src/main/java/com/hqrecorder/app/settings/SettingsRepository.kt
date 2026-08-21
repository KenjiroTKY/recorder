package com.hqrecorder.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hqrecorder.app.audio.AudioFormatType
import com.hqrecorder.app.audio.AudioQuality
import com.hqrecorder.app.audio.GainProcessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val FORMAT = stringPreferencesKey("quality_format")
        val SAMPLE_RATE = intPreferencesKey("quality_sample_rate")
        val BIT_DEPTH = intPreferencesKey("quality_bit_depth")
        val AAC_BITRATE = intPreferencesKey("quality_aac_bitrate")
        val QUALITY_LABEL = stringPreferencesKey("quality_label")
        val SAVE_FOLDER_URI = stringPreferencesKey("save_folder_uri")
        val CERT_ENABLED = booleanPreferencesKey("certificate_enabled")
        val TSA_URL = stringPreferencesKey("tsa_url")
        val TSA_AUTH_HEADER = stringPreferencesKey("tsa_auth_header")
        val AUDIO_FOCUS_POLICY = stringPreferencesKey("audio_focus_policy")
        val TRUSTED_ROOT_CAS = stringPreferencesKey("trusted_root_ca_pems")
        val GAIN_DB = floatPreferencesKey("gain_db")
        val AUTO_GAIN_REDUCTION_ENABLED = booleanPreferencesKey("auto_gain_reduction_enabled")
    }

    private val caListSerializer = ListSerializer(String.serializer())
    private fun decodeCaList(raw: String?): List<String> =
        raw?.let { runCatching { Json.decodeFromString(caListSerializer, it) }.getOrNull() } ?: emptyList()
    private fun encodeCaList(list: List<String>): String = Json.encodeToString(caListSerializer, list)

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val format = prefs[Keys.FORMAT]?.let { runCatching { AudioFormatType.valueOf(it) }.getOrNull() }
            ?: AudioQuality.STANDARD_WAV.formatType
        val quality = AudioQuality(
            formatType = format,
            sampleRateHz = prefs[Keys.SAMPLE_RATE] ?: AudioQuality.STANDARD_WAV.sampleRateHz,
            bitDepth = prefs[Keys.BIT_DEPTH] ?: AudioQuality.STANDARD_WAV.bitDepth,
            aacBitrateBps = prefs[Keys.AAC_BITRATE] ?: AudioQuality.STANDARD_WAV.aacBitrateBps,
            label = prefs[Keys.QUALITY_LABEL] ?: AudioQuality.STANDARD_WAV.label
        )
        AppSettings(
            quality = quality,
            saveFolderUri = prefs[Keys.SAVE_FOLDER_URI],
            certificateEnabled = prefs[Keys.CERT_ENABLED] ?: false,
            tsaUrl = prefs[Keys.TSA_URL] ?: AppSettings.DEFAULT_TSA_URL,
            tsaAuthHeader = prefs[Keys.TSA_AUTH_HEADER],
            audioFocusPolicy = prefs[Keys.AUDIO_FOCUS_POLICY]?.let {
                runCatching { AudioFocusPolicy.valueOf(it) }.getOrNull()
            } ?: AudioFocusPolicy.PAUSE,
            trustedRootCaPems = decodeCaList(prefs[Keys.TRUSTED_ROOT_CAS]),
            gainDb = prefs[Keys.GAIN_DB]?.let { GainProcessor.clampGainDb(it) } ?: GainProcessor.DEFAULT_GAIN_DB,
            autoGainReductionEnabled = prefs[Keys.AUTO_GAIN_REDUCTION_ENABLED] ?: false
        )
    }

    suspend fun updateQuality(quality: AudioQuality) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FORMAT] = quality.formatType.name
            prefs[Keys.SAMPLE_RATE] = quality.sampleRateHz
            prefs[Keys.BIT_DEPTH] = quality.bitDepth
            prefs[Keys.AAC_BITRATE] = quality.aacBitrateBps
            prefs[Keys.QUALITY_LABEL] = quality.label
        }
    }

    suspend fun updateSaveFolderUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.SAVE_FOLDER_URI) else prefs[Keys.SAVE_FOLDER_URI] = uri
        }
    }

    suspend fun updateCertificateEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.CERT_ENABLED] = enabled }
    }

    suspend fun updateTsaUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[Keys.TSA_URL] = url }
    }

    suspend fun updateTsaAuthHeader(header: String?) {
        context.dataStore.edit { prefs ->
            if (header.isNullOrBlank()) prefs.remove(Keys.TSA_AUTH_HEADER) else prefs[Keys.TSA_AUTH_HEADER] = header
        }
    }

    suspend fun updateAudioFocusPolicy(policy: AudioFocusPolicy) {
        context.dataStore.edit { prefs -> prefs[Keys.AUDIO_FOCUS_POLICY] = policy.name }
    }

    suspend fun addTrustedRootCa(pem: String) {
        context.dataStore.edit { prefs ->
            val current = decodeCaList(prefs[Keys.TRUSTED_ROOT_CAS])
            if (pem !in current) {
                prefs[Keys.TRUSTED_ROOT_CAS] = encodeCaList(current + pem)
            }
        }
    }

    suspend fun removeTrustedRootCa(pem: String) {
        context.dataStore.edit { prefs ->
            val current = decodeCaList(prefs[Keys.TRUSTED_ROOT_CAS])
            prefs[Keys.TRUSTED_ROOT_CAS] = encodeCaList(current - pem)
        }
    }

    suspend fun updateGainDb(db: Float) {
        context.dataStore.edit { prefs -> prefs[Keys.GAIN_DB] = GainProcessor.clampGainDb(db) }
    }

    suspend fun updateAutoGainReductionEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.AUTO_GAIN_REDUCTION_ENABLED] = enabled }
    }
}
