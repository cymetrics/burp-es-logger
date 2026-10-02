package io.cymetrics.eslogger

import io.cymetrics.eslogger.upload.FailurePolicy
import io.cymetrics.eslogger.upload.FailurePolicy.BatchOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 失敗怎麼分類，決定了「ES 暫時掛掉」和「這筆永遠進不去」會不會被混為一談。
 * 混在一起的後果是：一筆 ES 永遠拒絕的文件會卡住它後面所有紀錄。
 */
class FailurePolicyTest {

    @Test
    fun `server errors are retried, because the data is fine and the server is not`() {
        assertEquals(BatchOutcome.RETRY_LATER, FailurePolicy.classifyHttp(503, batchSize = 500))
        assertEquals(BatchOutcome.RETRY_LATER, FailurePolicy.classifyHttp(500, batchSize = 1))
    }

    @Test
    fun `throttling is retried rather than treated as a bad batch`() {
        assertEquals(BatchOutcome.RETRY_LATER, FailurePolicy.classifyHttp(429, batchSize = 500))
    }

    @Test
    fun `a rejected multi-document batch is split to find the offending record`() {
        assertEquals(BatchOutcome.SPLIT_BATCH, FailurePolicy.classifyHttp(400, batchSize = 500))
    }

    @Test
    fun `a single document the server keeps rejecting is dropped so the queue can move`() {
        assertEquals(BatchOutcome.DROP_BATCH, FailurePolicy.classifyHttp(400, batchSize = 1))
    }

    @Test
    fun `a wrong endpoint is a configuration error, never a reason to destroy records`() {
        // 404 = endpoint 打錯字、少了路徑前綴、或指到 Kibana。若當成「資料有問題」，
        // 切批會一路切到單筆然後逐筆丟棄，一個字母的錯字就會刪光整場測試的證據。
        assertEquals(BatchOutcome.RETRY_LATER, FailurePolicy.classifyHttp(404, batchSize = 500))
        assertEquals(BatchOutcome.RETRY_LATER, FailurePolicy.classifyHttp(404, batchSize = 1))
        assertEquals(BatchOutcome.RETRY_LATER, FailurePolicy.classifyHttp(405, batchSize = 1))
    }

    @Test
    fun `a payload too large is shrunk, not discarded`() {
        // 413 代表「這批太大」，不是「這筆資料壞掉」。單筆仍然太大時要縮小上限重送，
        // 丟掉它等於拿中介設備的限制去刪稽核紀錄。
        assertEquals(BatchOutcome.SPLIT_BATCH, FailurePolicy.classifyHttp(413, batchSize = 2))
        assertEquals(BatchOutcome.SHRINK_LIMIT, FailurePolicy.classifyHttp(413, batchSize = 1))
    }

    @Test
    fun `authentication failures are retried, not dropped`() {
        // 401 / 403 通常是金鑰填錯或被撤銷，資料本身沒問題 —— 丟掉等於懲罰使用者的設定錯誤
        assertEquals(BatchOutcome.RETRY_LATER, FailurePolicy.classifyHttp(401, batchSize = 1))
        assertEquals(BatchOutcome.RETRY_LATER, FailurePolicy.classifyHttp(403, batchSize = 500))
    }

    @Test
    fun `a document is given a few attempts before being dropped`() {
        assertFalse(FailurePolicy.shouldDropDocument(attempts = 1))
        assertFalse(FailurePolicy.shouldDropDocument(attempts = 2))
        assertTrue(FailurePolicy.shouldDropDocument(attempts = FailurePolicy.MAX_DOC_ATTEMPTS))
        assertTrue(FailurePolicy.shouldDropDocument(attempts = FailurePolicy.MAX_DOC_ATTEMPTS + 1))
    }
}
