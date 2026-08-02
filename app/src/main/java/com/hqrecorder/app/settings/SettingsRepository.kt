package com.hqrecorder.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hqrecorder.app.audio.AudioFormatType
import com.hqrecorder.app.audio.AudioQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    }

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
            tsaUrl = prefs[Keys.TSA_URL] ?: "",
            tsaAuthHeader = prefs[Keys.TSA_AUTH_HEADER]
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
}
