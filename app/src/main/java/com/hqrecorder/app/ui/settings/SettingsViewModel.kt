package com.hqrecorder.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hqrecorder.app.HqRecorderApp
import com.hqrecorder.app.audio.AudioQuality
import com.hqrecorder.app.audio.RecordingState
import com.hqrecorder.app.controller.RecordingController
import com.hqrecorder.app.settings.AppSettings
import com.hqrecorder.app.settings.AudioFocusPolicy
import com.hqrecorder.app.storage.SafStorageManager
import com.hqrecorder.app.storage.SaveFolderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = getApplication<HqRecorderApp>().container.settingsRepository
    private val recordingController = RecordingController(application)

    val settings: StateFlow<AppSettings> = repo.settingsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings()
    )

    // 保存先フォルダの表示ステータス(SPEC.md 3.3.1)。フォルダ選択が変わるたびアクセス可否を再確認する。
    val folderStatus: StateFlow<SaveFolderStatus> = settings
        .map { it.saveFolderUri }
        .distinctUntilChanged()
        .map { uri -> withContext(Dispatchers.IO) { SafStorageManager.resolveFolderStatus(getApplication(), uri) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SaveFolderStatus.NotSet)

    // 録音中は保存先変更を無効化するため(SPEC.md 3.3.2)、進行中のRecordingServiceの状態を監視する。
    val isRecordingInProgress: StateFlow<Boolean> = recordingController.uiState
        .map { it.state != RecordingState.IDLE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val presets = AudioQuality.presets

    init {
        recordingController.bind()
    }

    fun onFolderPicked(uri: Uri) {
        SafStorageManager.persistPermission(getApplication(), uri)
        viewModelScope.launch { repo.updateSaveFolderUri(uri.toString()) }
    }

    override fun onCleared() {
        super.onCleared()
        recordingController.unbind()
    }

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

    fun addTrustedRootCa(pem: String) {
        if (pem.isBlank()) return
        viewModelScope.launch { repo.addTrustedRootCa(pem) }
    }

    fun removeTrustedRootCa(pem: String) {
        viewModelScope.launch { repo.removeTrustedRootCa(pem) }
    }

    fun setGainDb(db: Float) {
        viewModelScope.launch { repo.updateGainDb(db) }
    }
}
