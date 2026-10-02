package io.cymetrics.eslogger

import io.cymetrics.eslogger.storage.Pending
import io.cymetrics.eslogger.upload.BulkResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * _bulk 回應的判讀。這是整個上傳迴圈裡最容易悄悄出錯的地方：
 * 誤判成功會讓本地刪掉實際沒進 ES 的紀錄，誤判失敗則會無限重送。
 */
class BulkResponseTest {

    private fun rows(n: Int) = (1..n).map {
        val json = """{"seq":$it}"""
        Pending(it.toLong(), "doc-$it", json, json.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `created documents are accepted`() {
        val body = """{"items":[{"create":{"_id":"doc-1","status":201}}]}"""
        val result = BulkResponse.parse(body, rows(1))
        assertEquals(listOf(1L), result.accepted.map { it.seq })
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `conflict means the document is already stored, so it counts as accepted`() {
        // 重送時 ES 會以 409 拒絕同一個 _id。那代表資料已經在裡面了，
        // 若當成失敗就會永遠重送同一批。
        val body = """{"items":[{"create":{"_id":"doc-1","status":409}}]}"""
        val result = BulkResponse.parse(body, rows(1))
        assertEquals(listOf(1L), result.accepted.map { it.seq })
    }

    @Test
    fun `rejected documents are reported with their reason and not accepted`() {
        val body = """
            {"items":[
              {"create":{"_id":"doc-1","status":201}},
              {"create":{"_id":"doc-2","status":400,"error":{"type":"mapper_parsing_exception"}}}
            ]}
        """.trimIndent()
        val result = BulkResponse.parse(body, rows(2))
        assertEquals(listOf(1L), result.accepted.map { it.seq })
        assertEquals(listOf(2L), result.rejected.map { it.first.seq })
        assertTrue(result.rejected.single().second.contains("mapper_parsing_exception"))
    }

    @Test
    fun `a response without an items array is treated as fully accepted`() {
        // 只有在 HTTP 2xx 的情況下才會走到這裡，保守視為全部成功，
        // 否則會把已經寫進 ES 的資料重送一輪。
        val result = BulkResponse.parse("""{"took":3,"errors":false}""", rows(2))
        assertEquals(listOf(1L, 2L), result.accepted.map { it.seq })
    }

    @Test
    fun `unparseable response is treated as fully accepted rather than crashing the loop`() {
        val result = BulkResponse.parse("not json at all", rows(1))
        assertEquals(listOf(1L), result.accepted.map { it.seq })
    }

    @Test
    fun `items beyond the batch size are ignored`() {
        val body = """{"items":[{"create":{"_id":"doc-1","status":201}},{"create":{"_id":"ghost","status":201}}]}"""
        val result = BulkResponse.parse(body, rows(1))
        assertEquals(1, result.accepted.size)
    }
}
