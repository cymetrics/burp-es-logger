package io.cymetrics.eslogger

import io.cymetrics.eslogger.integrity.Hashing
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

    private fun record(seq: Long, payload: String = "x") =
        NewRecord(seq, "doc-$seq", "hash-$seq", payload, payload.toByteArray(Charsets.UTF_8).size)

    @Test
    fun `inserting advances the chain tip and the pending count`() {
        val spool = MemorySpool()

        spool.insertAll(listOf(record(1), record(2)))

        assertEquals(2, spool.pendingCount)
        assertEquals(2, spool.lastSeq)
        assertEquals("hash-2", spool.lastHash)
    }

    @Test
    fun `exceeding the budget drops the oldest records and counts them`() {
        val spool = MemorySpool(maxBytes = 30)

        spool.insertAll(listOf(record(1, "a".repeat(20)), record(2, "b".repeat(20))))

        assertEquals(1, spool.pendingCount)
        assertEquals(1, spool.droppedCount)
        assertEquals(listOf(2L), spool.pendingBatch(10).map { it.seq })
    }

    @Test
    fun `the budget counts UTF-8 bytes, not UTF-16 characters`() {
        // 中文 JSON 的 UTF-8 長度是 String.length 的三倍。若用字元數當位元組數，
        // 32 MB 的上限實際會吃到約 96 MB —— 純記憶體模式的有界記憶體承諾就破了。
        val chinese = "測".repeat(10)          // 10 chars, 30 bytes
        val spool = MemorySpool(maxBytes = 40)

        spool.insertAll(listOf(record(1, chinese), record(2, chinese)))

        assertEquals(1, spool.pendingCount, "two 30-byte records must not both fit in a 40-byte budget")
        assertEquals(30, spool.usageBytes())
    }

    @Test
    fun `purging removes only the confirmed records and frees their bytes`() {
        val spool = MemorySpool()
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
        val spool = MemorySpool()
        spool.insertAll(listOf(record(1)))

        assertEquals(0, spool.purge(listOf(99L)))
        assertEquals(1, spool.pendingCount)
    }

    @Test
    fun `a batch is limited and ordered by sequence`() {
        val spool = MemorySpool()
        spool.insertAll((1L..5L).map { record(it) })

        assertEquals(listOf(1L, 2L, 3L), spool.pendingBatch(3).map { it.seq })
    }
}
