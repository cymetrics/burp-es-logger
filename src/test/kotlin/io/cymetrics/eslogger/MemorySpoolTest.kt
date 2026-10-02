package io.cymetrics.eslogger

import io.cymetrics.eslogger.integrity.Hashing
import io.cymetrics.eslogger.storage.ChainTipStore
import io.cymetrics.eslogger.storage.MemorySpool
import io.cymetrics.eslogger.storage.NewRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 純記憶體 outbox。這是預設模式，所以「什麼時候丟資料、丟了有沒有被記下來」
 * 必須明確 —— 使用者接受缺口，但缺口不能是無聲的。
 */
class MemorySpoolTest {

    private class FakeChainTip(var seq: Long = 0, var hash: String = Hashing.GENESIS) : ChainTipStore {
        override fun loadChainTip(): Pair<Long, String> = seq to hash
        override fun saveChainTip(seq: Long, hash: String) {
            this.seq = seq
            this.hash = hash
        }
    }

    private fun record(seq: Long, payload: String = "x") =
        NewRecord(seq, "doc-$seq", "hash-$seq", payload)

    @Test
    fun `inserting advances the chain tip and the pending count`() {
        val tip = FakeChainTip()
        val spool = MemorySpool(tip)

        spool.insertAll(listOf(record(1), record(2)))

        assertEquals(2, spool.pendingCount)
        assertEquals(2, spool.lastSeq)
        assertEquals("hash-2", spool.lastHash)
    }

    @Test
    fun `the chain tip is persisted so a reload continues the sequence`() {
        // 沒有這個行為，重載 extension 後 seq 會從 1 重來，
        // 同一個 index 裡就會出現重複的 seq，驗證時無法分辨順序
        val tip = FakeChainTip()
        MemorySpool(tip).insertAll(listOf(record(7)))

        val afterReload = MemorySpool(tip)

        assertEquals(7, afterReload.lastSeq)
        assertEquals("hash-7", afterReload.lastHash)
    }

    @Test
    fun `exceeding the budget drops the oldest records and counts them`() {
        val spool = MemorySpool(FakeChainTip(), maxBytes = 30)

        spool.insertAll(listOf(record(1, "a".repeat(20)), record(2, "b".repeat(20))))

        assertEquals(1, spool.pendingCount)
        assertEquals(1, spool.droppedCount)
        assertEquals(listOf(2L), spool.pendingBatch(10).map { it.seq })
    }

    @Test
    fun `purging removes only the confirmed records and frees their bytes`() {
        val spool = MemorySpool(FakeChainTip())
        spool.insertAll(listOf(record(1), record(2), record(3)))
        val before = spool.usageBytes()

        val removed = spool.purge(listOf(1L, 3L))

        assertEquals(2, removed)
        assertEquals(listOf(2L), spool.pendingBatch(10).map { it.seq })
        assertEquals(1, spool.pendingCount)
        assertTrue(spool.usageBytes() < before)
    }

    @Test
    fun `purging a sequence that is not held changes nothing`() {
        val spool = MemorySpool(FakeChainTip())
        spool.insertAll(listOf(record(1)))

        assertEquals(0, spool.purge(listOf(99L)))
        assertEquals(1, spool.pendingCount)
    }

    @Test
    fun `a batch is limited and ordered by sequence`() {
        val spool = MemorySpool(FakeChainTip())
        spool.insertAll((1L..5L).map { record(it) })

        assertEquals(listOf(1L, 2L, 3L), spool.pendingBatch(3).map { it.seq })
    }
}
