package com.hqrecorder.app.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hqrecorder.app.HqRecorderApp
import com.hqrecorder.app.controller.RecordingController
import com.hqrecorder.app.service.RecordingUiState
import com.hqrecorder.app.settings.AppSettings
import com.hqrecorder.app.storage.SafStorageManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<HqRecorderApp>()
    private val settingsRepository = app.container.settingsRepository
    private val controller = RecordingController(application)

    val recordingUiState: StateFlow<RecordingUiState> = controller.uiState

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings()
    )

    init {
        controller.bind()
    }

    fun onFolderPicked(uri: Uri) {
        SafStorageManager.persistPermission(getApplication(), uri)
        viewModelScope.launch { settingsRepository.updateSaveFolderUri(uri.toString()) }
    }

    fun startRecording() {
        val folder = settings.value.saveFolderUri?.let { Uri.parse(it) } ?: return
        controller.startRecording(settings.value.quality, folder)
    }

    fun pauseRecording() = controller.pause()
    fun resumeRecording() = controller.resume()
    fun stopRecording() = controller.stop()

    override fun onCleared() {
        super.onCleared()
        controller.unbind()
    }
}
