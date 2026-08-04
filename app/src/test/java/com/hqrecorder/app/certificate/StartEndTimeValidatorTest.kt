package com.hqrecorder.app.certificate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** TEST_SPEC.md 2.6.1: 開始時刻証明と終了時刻証明の順序整合性の検証。 */
class StartEndTimeValidatorTest {

    @Test
    fun isValidOrder_startBeforeEnd_isTrue() {
        assertTrue(StartEndTimeValidator.isValidOrder(Date(1_000L), Date(2_000L)))
    }

    @Test
    fun isValidOrder_startAfterEnd_isFalse() {
        assertFalse(StartEndTimeValidator.isValidOrder(Date(3_000L), Date(2_000L)))
    }

    @Test
    fun isValidOrder_startEqualsEnd_isFalse() {
        val time = Date(2_000L)
        assertFalse(StartEndTimeValidator.isValidOrder(time, time))
    }
}
