package com.hqrecorder.app.storage

/**
 * 設定画面での保存先表示用ステータス(SPEC.md 3.3.1)。
 */
sealed class SaveFolderStatus {
    object NotSet : SaveFolderStatus()
    data class Accessible(val displayName: String) : SaveFolderStatus()
    object Inaccessible : SaveFolderStatus()
}
