package com.hqrecorder.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hqrecorder.app.HqRecorderApp
import com.hqrecorder.app.audio.AudioQuality
import com.hqrecorder.app.settings.AppSettings
import com.hqrecorder.app.settings.AudioFocusPolicy
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = getApplication<HqRecorderApp>().container.settingsRepository

    val settings: StateFlow<AppSettings> = repo.settingsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings()
    )

    val presets = AudioQuality.presets

    fun selectQuality(quality: AudioQuality) {
        viewModelScope.launch { repo.updateQuality(quality) }
    }

    fun setCertificateEnabled(enabled: Boolean) {
        viewModelScope.launch { repo.updateCertificateEnabled(enabled) }
    }

    fun setTsaUrl(url: String) {
        viewModelScope.launch { repo.updateTsaUrl(url) }
    }

    fun setTsaAuthHeader(header: String) {
        viewModelScope.launch { repo.updateTsaAuthHeader(header) }
    }

    fun setAudioFocusPolicy(policy: AudioFocusPolicy) {
        viewModelScope.launch { repo.updateAudioFocusPolicy(policy) }
    }
}
