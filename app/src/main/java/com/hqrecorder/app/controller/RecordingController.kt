package com.hqrecorder.app.controller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.hqrecorder.app.audio.AudioQuality
import com.hqrecorder.app.service.RecordingService
import com.hqrecorder.app.service.RecordingUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Activity/ViewModel側からRecordingService(Foreground Service)をbindし、
 * StateFlowを中継する薄いブリッジ。画面回転やActivity再生成の影響を受けずに録音を継続できる。
 */
class RecordingController(private val appContext: Context) {

    private var service: RecordingService? = null
    private var bound = false
    private var connectedSignal = CompletableDeferred<Unit>()

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private var collectorJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? RecordingService.LocalBinder ?: return
            service = local.getService()
            bound = true
            collectorJob = scope.launch {
                service!!.uiState.collect { _uiState.value = it }
            }
            if (!connectedSignal.isCompleted) connectedSignal.complete(Unit)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
            collectorJob?.cancel()
            connectedSignal = CompletableDeferred()
        }
    }

    fun bind() {
        if (bound) return
        val intent = Intent(appContext, RecordingService::class.java)
        appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        if (bound) {
            appContext.unbindService(connection)
            bound = false
        }
    }

    fun startRecording(quality: AudioQuality, folderUri: Uri, preferUnprocessed: Boolean) {
        val intent = Intent(appContext, RecordingService::class.java)
        ContextCompat.startForegroundService(appContext, intent)
        scope.launch {
            if (!bound) bind()
            connectedSignal.await()
            service?.startRecording(quality, folderUri, preferUnprocessed)
        }
    }

    fun pause() { service?.pauseRecording() }
    fun resume() { service?.resumeRecording() }
    fun stop() { service?.stopRecording() }
}
