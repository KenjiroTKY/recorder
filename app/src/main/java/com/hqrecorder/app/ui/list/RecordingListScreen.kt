package com.hqrecorder.app.ui.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hqrecorder.app.certificate.VerificationResult
import com.hqrecorder.app.storage.CertificateStatus
import com.hqrecorder.app.storage.RecordingMetadata

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingListScreen(onBack: () -> Unit, viewModel: RecordingListViewModel = viewModel()) {
    val recordings by viewModel.recordings.collectAsState()
    val verificationResult by viewModel.verificationResult.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("録音一覧") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            items(recordings, key = { it.id }) { recording ->
                RecordingRow(
                    recording = recording,
                    playbackState = playbackState,
                    onRetryCertificate = { viewModel.retryCertificate(recording) },
                    onVerify = { viewModel.verify(recording) },
                    onPlayPauseToggle = {
                        if (playbackState.playingId == recording.id && playbackState.isPlaying) {
                            viewModel.pause()
                        } else {
                            viewModel.playOrResume(recording)
                        }
                    },
                    onSeek = { viewModel.seekTo(it) }
                )
                Divider()
            }
        }
    }

    verificationResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.clearVerificationResult() },
            confirmButton = {
                TextButton(onClick = { viewModel.clearVerificationResult() }) { Text("閉じる") }
            },
            title = { Text("検証結果") },
            text = {
                when (result) {
                    is VerificationResult.Valid ->
                        Text("有効な証明書です。\n発行時刻: ${result.timestamp}\nTSA: ${result.tsaName ?: "不明"}")
                    is VerificationResult.Invalid ->
                        Text("検証に失敗しました: ${result.reason}")
                }
            }
        )
    }
}

@Composable
private fun RecordingRow(
    recording: RecordingMetadata,
    playbackState: PlaybackUiState,
    onRetryCertificate: () -> Unit,
    onVerify: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onSeek: (Long) -> Unit
) {
    val isCurrent = playbackState.playingId == recording.id
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(recording.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${recording.formatType} ${recording.sampleRateHz}Hz  ${formatDuration(recording.durationMs)}  ${formatSize(recording.fileSizeBytes)}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(certificateStatusLabel(recording.certificateStatus), style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onPlayPauseToggle) {
                Icon(
                    if (isCurrent && playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isCurrent && playbackState.isPlaying) "一時停止" else "再生"
                )
            }
            when (CertificateStatus.valueOf(recording.certificateStatus)) {
                CertificateStatus.ISSUED -> TextButton(onClick = onVerify) { Text("検証") }
                CertificateStatus.FAILED, CertificateStatus.PENDING -> TextButton(onClick = onRetryCertificate) { Text("再試行") }
                CertificateStatus.NONE -> Unit
            }
        }
        if (isCurrent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatDuration(playbackState.positionMs), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = playbackState.positionMs.toFloat(),
                    valueRange = 0f..playbackState.durationMs.coerceAtLeast(1L).toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(formatDuration(playbackState.durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun certificateStatusLabel(status: String) = when (CertificateStatus.valueOf(status)) {
    CertificateStatus.NONE -> "証明書なし"
    CertificateStatus.PENDING -> "証明書: 発行待ち"
    CertificateStatus.ISSUED -> "証明書: 発行済み"
    CertificateStatus.FAILED -> "証明書: 発行失敗"
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return "%.1fMB".format(mb)
}
