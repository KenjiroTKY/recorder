package com.hqrecorder.app.audio

import com.hqrecorder.app.settings.AudioFocusPolicy

/** Android の AudioManager フォーカス変化定数から変換した、Android非依存の種別。 */
enum class AudioFocusChangeType { LOST_PERMANENTLY, LOST_TRANSIENT, GAINED }

enum class AudioFocusAction { PAUSE, RESUME, NONE }

/**
 * AudioFocus変化・ユーザー方針・現在自動一時停止中かどうかから録音側で取るべきアクションを判定する純粋関数。
 * DESIGN.md 4.7節参照。
 */
object AudioFocusDecision {
    fun decide(
        changeType: AudioFocusChangeType,
        policy: AudioFocusPolicy,
        pausedByFocusLoss: Boolean
    ): AudioFocusAction {
        return when (changeType) {
            AudioFocusChangeType.GAINED ->
                if (pausedByFocusLoss) AudioFocusAction.RESUME else AudioFocusAction.NONE
            AudioFocusChangeType.LOST_PERMANENTLY, AudioFocusChangeType.LOST_TRANSIENT ->
                if (policy == AudioFocusPolicy.PAUSE) AudioFocusAction.PAUSE else AudioFocusAction.NONE
        }
    }
}
