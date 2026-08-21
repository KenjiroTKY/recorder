package com.hqrecorder.app.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 録音履歴メタデータを app 内部ストレージのJSONファイルへ永続化するリポジトリ。
 * 件数規模が大きくならないICレコーダー用途を想定し、Roomではなく軽量なJSONインデックスを採用。
 */
class RecordingRepository(private val context: Context) {

    private val indexFile: File
        get() = File(context.filesDir, "recordings_index.json")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _recordings = MutableStateFlow<List<RecordingMetadata>>(emptyList())
    val recordings: StateFlow<List<RecordingMetadata>> = _recordings.asStateFlow()

    init {
        load()
    }

    private fun load() {
        _recordings.value = if (indexFile.exists()) {
            runCatching {
                json.decodeFromString<List<RecordingMetadata>>(indexFile.readText())
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    private fun persist() {
        indexFile.writeText(json.encodeToString(_recordings.value))
    }

    @Synchronized
    fun addRecording(metadata: RecordingMetadata) {
        _recordings.value = listOf(metadata) + _recordings.value
        persist()
    }

    @Synchronized
    fun updateRecording(metadata: RecordingMetadata) {
        _recordings.value = _recordings.value.map { if (it.id == metadata.id) metadata else it }
        persist()
    }

    @Synchronized
    fun removeRecording(id: String) {
        _recordings.value = _recordings.value.filterNot { it.id == id }
        persist()
    }

    fun findById(id: String): RecordingMetadata? = _recordings.value.find { it.id == id }

    /**
     * 保存先フォルダの実ファイルと同期し、インデックス未登録のファイルをインポートする(SPEC.md 3.9)。
     * SAFのフォルダ走査を含みブロッキングI/Oを伴うため、呼び出し側はIOディスパッチャ上で実行すること。
     * 戻り値は、フォルダ上に実体が見つからなかった既存録音のID(一覧側での警告表示に利用)。
     */
    @Synchronized
    fun syncWithFolders(folderUris: Set<String>): Set<String> {
        val result = RecordingFolderScanner.scan(context, _recordings.value, folderUris)
        if (result.imported.isNotEmpty()) {
            _recordings.value = result.imported + _recordings.value
            persist()
        }
        return result.missingIds
    }
}
