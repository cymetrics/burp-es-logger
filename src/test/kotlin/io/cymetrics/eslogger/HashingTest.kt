package io.cymetrics.eslogger

import io.cymetrics.eslogger.integrity.Hashing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** 雜湊與鏈結規則。這些值會寫進報告，變動等同於讓既有紀錄無法驗證。 */
class HashingTest {

    @Test
    fun `genesis is sixty four zeros`() {
        assertEquals("0".repeat(64), Hashing.GENESIS)
    }

    @Test
    fun `hashing a range matches hashing the copied slice`() {
        // 擷取路徑靠這個等價性避免為了算 hash 再複製一份 body
        val raw = "HEAD\r\n\r\nBODYBODY".toByteArray()
        val offset = 8
        assertEquals(
            Hashing.sha256Hex(raw.copyOfRange(offset, raw.size)),
            Hashing.sha256Hex(raw, offset, raw.size - offset)
        )
    }

    @Test
    fun `the same record under a different predecessor hashes differently`() {
        val material = listOf("1", "doc", "http", "", "", "Proxy", "GET", "https://x/", "200", "a", "b", "c", "d")
        assertNotEquals(
            Hashing.recordHash(material, Hashing.GENESIS),
            Hashing.recordHash(material, "f".repeat(64))
        )
    }

    @Test
    fun `swapping two fields changes the hash`() {
        // 分隔字元若被內容撞到，重排欄位就可能算出同一個 hash —— 那會讓竄改無法偵測
        val a = listOf("1", "doc", "http", "GET", "https://x/")
        val b = listOf("1", "doc", "http", "https://x/", "GET")
        assertNotEquals(Hashing.recordHash(a, Hashing.GENESIS), Hashing.recordHash(b, Hashing.GENESIS))
    }

    @Test
    fun `a field containing the separator cannot impersonate two fields`() {
        val split = listOf("a", "b")
        val joined = listOf("a\u001fb")
        assertNotEquals(Hashing.recordHash(split, Hashing.GENESIS), Hashing.recordHash(joined, Hashing.GENESIS))
    }
}
