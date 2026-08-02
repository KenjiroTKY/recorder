package com.hqrecorder.app.core

import android.content.Context
import com.hqrecorder.app.certificate.CertificateManager
import com.hqrecorder.app.settings.SettingsRepository
import com.hqrecorder.app.storage.RecordingRepository

/**
 * Hilt等を使わない手動DIコンテナ。Applicationが1つ保持し、各層はここから依存を取得する。
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository = SettingsRepository(appContext)
    val recordingRepository = RecordingRepository(appContext)
    val certificateManager = CertificateManager(appContext, recordingRepository)
}
