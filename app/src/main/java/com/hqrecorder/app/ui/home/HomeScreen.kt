package com.hqrecorder.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hqrecorder.app.audio.RecordingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenRecordings: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.recordingUiState.collectAsState()
    val settings by viewModel.settings.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.onFolderPicked(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("高音質レコーダー") },
                actions = {
                    IconButton(onClick = onOpenRecordings) {
                        Icon(Icons.Filled.List, contentDescription = "録音一覧")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "設定")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = settings.quality.label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))

            if (settings.saveFolderUri == null) {
                Text("保存先フォルダが未設定です", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { folderPicker.launch(null) }) { Text("保存先フォルダを選択") }
            } else {
                Text("保存先: 設定済み")
            }

            Spacer(Modifier.height(32.dp))
            Text(text = formatElapsed(uiState.elapsedMs), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            LevelMeterRow(
                left = uiState.level.leftPeak,
                right = uiState.level.rightPeak,
                isStereo = uiState.isStereo,
                clipped = uiState.level.clipped
            )

            Spacer(Modifier.height(32.dp))

            when (uiState.state) {
                RecordingState.IDLE -> Button(
                    onClick = { viewModel.startRecording() },
                    enabled = settings.saveFolderUri != null
                ) { Text("録音開始") }

                RecordingState.RECORDING -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { viewModel.pauseRecording() }) { Text("一時停止") }
                    Button(onClick = { viewModel.stopRecording() }) { Text("停止") }
                }

                RecordingState.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { viewModel.resumeRecording() }) { Text("再開") }
                    Button(onClick = { viewModel.stopRecording() }) { Text("停止") }
                }

                RecordingState.STOPPING -> Text("保存中...")
            }

            uiState.errorMessage?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
