package com.hqrecorder.app.audio

import com.hqrecorder.app.settings.AudioFocusPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST_SPEC.md 2.4.5a: AudioFocus変化とユーザー設定からのアクション判定の検証。 */
class AudioFocusDecisionTest {

    @Test
    fun policyPause_permanentLoss_pauses() {
        val action = AudioFocusDecision.decide(
            AudioFocusChangeType.LOST_PERMANENTLY, AudioFocusPolicy.PAUSE, pausedByFocusLoss = false
        )
        assertEquals(AudioFocusAction.PAUSE, action)
    }

    @Test
    fun policyPause_transientLoss_pauses() {
        val action = AudioFocusDecision.decide(
            AudioFocusChangeType.LOST_TRANSIENT, AudioFocusPolicy.PAUSE, pausedByFocusLoss = false
        )
        assertEquals(AudioFocusAction.PAUSE, action)
    }

    @Test
    fun policyContinue_loss_doesNothing() {
        val permanentAction = AudioFocusDecision.decide(
            AudioFocusChangeType.LOST_PERMANENTLY, AudioFocusPolicy.CONTINUE, pausedByFocusLoss = false
        )
        val transientAction = AudioFocusDecision.decide(
            AudioFocusChangeType.LOST_TRANSIENT, AudioFocusPolicy.CONTINUE, pausedByFocusLoss = false
        )
        assertEquals(AudioFocusAction.NONE, permanentAction)
        assertEquals(AudioFocusAction.NONE, transientAction)
    }

    @Test
    fun gain_afterAutoPause_resumes() {
        val action = AudioFocusDecision.decide(
            AudioFocusChangeType.GAINED, AudioFocusPolicy.PAUSE, pausedByFocusLoss = true
        )
        assertEquals(AudioFocusAction.RESUME, action)
    }

    @Test
    fun gain_afterManualPause_doesNothing() {
        val action = AudioFocusDecision.decide(
            AudioFocusChangeType.GAINED, AudioFocusPolicy.PAUSE, pausedByFocusLoss = false
        )
        assertEquals(AudioFocusAction.NONE, action)
    }

    @Test
    fun gain_whileNotPaused_underContinuePolicy_doesNothing() {
        val action = AudioFocusDecision.decide(
            AudioFocusChangeType.GAINED, AudioFocusPolicy.CONTINUE, pausedByFocusLoss = false
        )
        assertEquals(AudioFocusAction.NONE, action)
    }
}
