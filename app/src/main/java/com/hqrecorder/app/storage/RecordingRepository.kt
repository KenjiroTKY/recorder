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
}
