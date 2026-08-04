package com.hqrecorder.app.certificate.custody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST_SPEC.md 2.6.4: Chain of Custodyログのハッシュ連結・改ざん検知の検証。 */
class CustodyLogChainTest {

    @Test
    fun append_firstEntry_hasNullPrevEntryHash() {
        val entry = CustodyLogChain.append(
            previous = null,
            timestampEpochMs = 1_000L,
            action = CustodyAction.CREATED,
            actor = "device-a",
            targetRecordingId = "rec-1"
        )

        assertNull(entry.prevEntryHash)
        assertEquals("CREATED", entry.action)
        assertTrue(entry.entryHash.isNotBlank())
    }

    @Test
    fun append_secondEntry_chainsToPreviousEntryHash() {
        val first = CustodyLogChain.append(null, 1_000L, CustodyAction.CREATED, "device-a", "rec-1")
        val second = CustodyLogChain.append(first, 2_000L, CustodyAction.VERIFIED, "device-a", "rec-1")

        assertEquals(first.entryHash, second.prevEntryHash)
    }

    @Test
    fun verify_ofUntamperedChain_returnsValid() {
        val entries = buildChain()

        val result = CustodyLogChain.verify(entries)

        assertTrue(result is CustodyLogVerificationResult.Valid)
    }

    @Test
    fun verify_ofEmptyList_returnsEmpty() {
        val result = CustodyLogChain.verify(emptyList())
        assertTrue(result is CustodyLogVerificationResult.Empty)
    }

    @Test
    fun verify_withFieldTamperedInMiddleEntry_detectsBrokenAtThatIndex() {
        val entries = buildChain().toMutableList()
        entries[1] = entries[1].copy(action = "EXPORTED") // entryHashを再計算せず値だけ改ざん

        val result = CustodyLogChain.verify(entries)

        assertTrue(result is CustodyLogVerificationResult.Broken)
        assertEquals(1, (result as CustodyLogVerificationResult.Broken).brokenAtIndex)
    }

    @Test
    fun verify_withBrokenPrevEntryHashLink_detectsBrokenAtSubsequentEntry() {
        val entries = buildChain().toMutableList()
        entries[2] = entries[2].copy(prevEntryHash = "not-the-real-previous-hash")

        val result = CustodyLogChain.verify(entries)

        assertTrue(result is CustodyLogVerificationResult.Broken)
        assertEquals(2, (result as CustodyLogVerificationResult.Broken).brokenAtIndex)
    }

    private fun buildChain(): List<CustodyLogEntry> {
        val e1 = CustodyLogChain.append(null, 1_000L, CustodyAction.CREATED, "device-a", "rec-1")
        val e2 = CustodyLogChain.append(e1, 2_000L, CustodyAction.COPIED, "device-a", "rec-1")
        val e3 = CustodyLogChain.append(e2, 3_000L, CustodyAction.VERIFIED, "device-a", "rec-1")
        return listOf(e1, e2, e3)
    }
}
