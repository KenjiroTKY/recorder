package com.hqrecorder.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hqrecorder.app.audio.GainProcessor
import com.hqrecorder.app.settings.AudioFocusPolicy
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsState()
    var tsaUrlField by remember(settings.tsaUrl) { mutableStateOf(settings.tsaUrl) }
    var newCaPemField by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
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
                .padding(16.dp)
        ) {
            item { Text("録音音質", style = MaterialTheme.typography.titleLarge) }

            items(viewModel.presets) { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.quality == preset,
                        onClick = { viewModel.selectQuality(preset) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(preset.label)
                }
            }

            item { Divider(modifier = Modifier.padding(vertical = 16.dp)) }

            item { Text("録音感度（ゲイン）", style = MaterialTheme.typography.titleLarge) }

            item {
                val maxGainDb = GainProcessor.maxGainDb(preferUnprocessed = settings.certificateEnabled)
                val displayedGainDb = settings.gainDb.coerceIn(GainProcessor.MIN_GAIN_DB, maxGainDb)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (settings.certificateEnabled) {
                            "電子証明書付与時はAGC非適用のマイク入力(UNPROCESSED)を優先するため、上限を${maxGainDb.roundToInt()}dBまで拡大しています"
                        } else {
                            "動画撮影と同様のマイク入力(CAMCORDER)を優先します"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val gainLabel = "%+.0f dB".format(displayedGainDb)
                        Text(gainLabel, style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { viewModel.setGainDb(GainProcessor.DEFAULT_GAIN_DB) }) {
                            Text("0dBにリセット")
                        }
                    }
                    Slider(
                        value = displayedGainDb,
                        onValueChange = { viewModel.setGainDb(it) },
                        valueRange = GainProcessor.MIN_GAIN_DB..maxGainDb,
                        steps = (maxGainDb - GainProcessor.MIN_GAIN_DB).roundToInt() - 1
                    )
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("クリッピング検出時に自動でゲインを下げる", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "音割れを検出するたびにゲインを${GainProcessor.AUTO_REDUCTION_STEP_DB.roundToInt()}dBずつ自動的に引き下げます（下限${GainProcessor.MIN_GAIN_DB.roundToInt()}dB）。上げ直しは手動で行ってください。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = settings.autoGainReductionEnabled,
                        onCheckedChange = { viewModel.setAutoGainReductionEnabled(it) }
                    )
                }
            }

            item { Divider(modifier = Modifier.padding(vertical = 16.dp)) }

            item { Text("通話・Bluetooth割り込み時の挙動", style = MaterialTheme.typography.titleLarge) }

            items(AudioFocusPolicy.entries.toList()) { policy ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.audioFocusPolicy == policy,
                        onClick = { viewModel.setAudioFocusPolicy(policy) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (policy) {
                            AudioFocusPolicy.PAUSE -> "一時停止する"
                            AudioFocusPolicy.CONTINUE -> "継続する"
                        }
                    )
                }
            }

            item { Divider(modifier = Modifier.padding(vertical = 16.dp)) }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("電子証明書を付与", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "RFC3161タイムスタンプ局に録音ファイルのハッシュ値を送信し、改ざん検知用の証明書を保存します。",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Switch(
                        checked = settings.certificateEnabled,
                        onCheckedChange = { viewModel.setCertificateEnabled(it) }
                    )
                }
            }

            if (settings.certificateEnabled) {
                item {
                    OutlinedTextField(
                        value = tsaUrlField,
                        onValueChange = {
                            tsaUrlField = it
                            viewModel.setTsaUrl(it)
                        },
                        label = { Text("TSAエンドポイントURL") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                }

                item { Divider(modifier = Modifier.padding(vertical = 16.dp)) }

                item {
                    Text("信頼ルートCA証明書(TSA証明書チェーン検証用)", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "登録すると証明書検証時にTSA署名者証明書がここに登録したルートCAへ辿れるか確認します。未登録の場合はチェーン検証を省略します。",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                items(settings.trustedRootCaPems) { pem ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            pem.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("-----") }
                                ?.take(32)?.plus("…") ?: pem.take(32),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.removeTrustedRootCa(pem) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "削除")
                        }
                    }
                }

                item {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(
                            value = newCaPemField,
                            onValueChange = { newCaPemField = it },
                            label = { Text("PEM形式のCA証明書を貼り付け") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(
                            onClick = {
                                viewModel.addTrustedRootCa(newCaPemField)
                                newCaPemField = ""
                            },
                            modifier = Modifier.padding(top = 4.dp)
                        ) { Text("追加") }
                    }
                }
            }
        }
    }
}
