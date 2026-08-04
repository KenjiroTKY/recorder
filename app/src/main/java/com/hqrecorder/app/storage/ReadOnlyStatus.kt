package com.hqrecorder.app.storage

/** 保存後の読み取り専用化(9.6)の結果。 */
enum class ReadOnlyStatus {
    /** 読み取り専用化に成功 */
    APPLIED,
    /** プロバイダが非対応のため読み取り専用化を試みなかった */
    UNSUPPORTED,
    /** 対応プロバイダだが読み取り専用化に失敗した */
    FAILED
}
