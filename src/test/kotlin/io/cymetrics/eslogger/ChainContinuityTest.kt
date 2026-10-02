package io.cymetrics.eslogger

import com.google.gson.JsonParser
import io.cymetrics.eslogger.config.Config
import io.cymetrics.eslogger.core.RecordWriter
import io.cymetrics.eslogger.fakes.FakeLogging
import io.cymetrics.eslogger.fakes.FakePreferences
import io.cymetrics.eslogger.fakes.RecordingSpool
import io.cymetrics.eslogger.integrity.Hashing
import io.cymetrics.eslogger.model.ReqData
import io.cymetrics.eslogger.model.RequestCaptured
import io.cymetrics.eslogger.model.RespData
import io.cymetrics.eslogger.model.ResponseCaptured
import io.cymetrics.eslogger.storage.NewRecord
import io.cymetrics.eslogger.storage.Pending
import io.cymetrics.eslogger.storage.RecordSpool
import io.cymetrics.eslogger.storage.SpoolKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * seq 與鏈尾在「換案子」與「寫入失敗」之後的行為。
 *
 * 這兩種情況都會讓同一個 seq 帶著不同內容出現兩次，而驗證者看到的會是「鏈分岔」——
 * 和遭人竄改的徵狀一模一樣。
 */
class ChainContinuityTest {

    private fun writer(prefs: FakePreferences, project: String, spool: RecordSpool): RecordWriter {
        val config = Config(prefs).apply {
            testerId = "zet"; projectId = project; persistLocally = false; save()
        }
        return RecordWriter(config, spool, FakeLogging())
    }

    private fun exchange(w: RecordWriter, id: Int) {
        val head = "GET /x HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray()
        w.submit(RequestCaptured(id, ReqData(Instant.now(), "Proxy", "GET", "https://t/$id", "t", 443, true, head, head.size)))
        val rh = "HTTP/1.1 200 OK\r\n\r\n".toByteArray()
        w.submit(ResponseCaptured(id, RespData(Instant.now(), 200, rh, rh.size)))
    }

    private fun run(w: RecordWriter, count: Int) {
        w.start()
        repeat(count) { exchange(w, it) }
        w.stop()
    }

    @Test
    fun `each target index keeps its own chain`() {
        // 鏈尾存在 Burp 偏好設定（使用者層級，跨專案共用），但 index 是每個案子一個。
        // 若共用同一個鏈尾，換 Project ID 之後新 index 的第一筆會宣稱 seq 1001、
        // prev_hash 指向一筆只存在於另一個 index 的紀錄 —— 驗證時看起來就是缺漏。
        val prefs = FakePreferences()

        val acme = RecordingSpool()
        run(writer(prefs, "acme", acme), 2)

        val beta = RecordingSpool()
        run(writer(prefs, "beta", beta), 1)

        assertEquals(listOf(1L, 2L), acme.written.map { it.seq })
        assertEquals(listOf(1L), beta.written.map { it.seq }, "a new index must start its own chain at 1")
        val first = JsonParser.parseString(beta.written.single().docJson).asJsonObject
        assertEquals(
            Hashing.GENESIS,
            first.getAsJsonObject("integrity").get("prev_hash").asString,
            "the first record of a new index must link to the genesis hash, not another index's record"
        )
    }

    @Test
    fun `returning to an earlier project continues that project's chain`() {
        val prefs = FakePreferences()
        val acme1 = RecordingSpool()
        run(writer(prefs, "acme", acme1), 2)
        run(writer(prefs, "beta", RecordingSpool()), 3)

        val acme2 = RecordingSpool()
        run(writer(prefs, "acme", acme2), 1)

        assertEquals(listOf(3L), acme2.written.map { it.seq })
        val doc = JsonParser.parseString(acme2.written.single().docJson).asJsonObject
        assertEquals(acme1.written.last().recordHash, doc.getAsJsonObject("integrity").get("prev_hash").asString)
    }

    @Test
    fun `a seq consumed by a failed write is never reissued`() {
        // 寫入持續失敗（磁碟滿、DB 被鎖）時，那些 seq 已經被用掉了。重載後若從舊的鏈尾
        // 重新發號，同一個 seq 會帶著不同內容出現兩次 —— 寧可留下缺號，也不要重複。
        val prefs = FakePreferences()
        val broken = object : RecordSpool {
            override val lastSeq = 0L
            override val lastHash = Hashing.GENESIS
            override val pendingCount = 0L
            override val droppedCount = 0L
            override val kind = SpoolKind.MEMORY
            override fun usageBytes() = 0L
            override fun insertAll(records: List<NewRecord>) = throw IllegalStateException("disk full")
            override fun pendingBatch(limit: Int): List<Pending> = emptyList()
            override fun purge(seqs: Collection<Long>) = 0
            override fun close() {}
        }
        run(writer(prefs, "acme", broken), 2)

        val healthy = RecordingSpool()
        run(writer(prefs, "acme", healthy), 1)

        assertTrue(
            healthy.written.single().seq > 2,
            "seq ${healthy.written.single().seq} was already consumed by the failed writes"
        )
    }
}
