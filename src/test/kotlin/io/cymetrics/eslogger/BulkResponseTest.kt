package io.cymetrics.eslogger

import io.cymetrics.eslogger.storage.Pending
import io.cymetrics.eslogger.upload.BulkResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFails

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
        assertEquals(listOf(2L), result.rejected.map { it.row.seq })
        assertTrue(result.rejected.single().reason.contains("mapper_parsing_exception"))
    }

    @Test
    fun `uncertain or misaligned responses never acknowledge a batch`() {
        val invalidBodies = listOf(
            "not json at all", "<html>login</html>", "{}", "{\"errors\":true}",
            "{\"items\":[]}", "{\"items\":[{}]}",
            "{\"items\":[{\"create\":{}}]}",
            "{\"items\":[{\"create\":{\"status\":\"201\"}}]}",
            "{\"items\":[{\"create\":{\"status\":201,\"_id\":\"wrong\"}}]}",
            "{\"items\":[{\"create\":{\"status\":201}},{\"create\":{\"status\":201}}]}"
        )
        for (body in invalidBodies) assertFails(body) { BulkResponse.parse(body, rows(1)) }
    }

    @Test
    fun `rejected item status is retained for retry classification`() {
        val body = """{"items":[{"create":{"status":429,"error":{"type":"es_rejected_execution_exception"}}}]}"""
        assertEquals(429, BulkResponse.parse(body, rows(1)).rejected.single().status)
    }
}
