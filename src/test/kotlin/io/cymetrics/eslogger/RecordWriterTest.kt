package io.cymetrics.eslogger

import com.google.gson.JsonObject
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
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * RecordWriter 掌管 seq、hash chain 與 material 欄位清單 —— 也就是這個工具全部的
 * 完整性主張。這裡的驗證器**獨立重算**文件（README / setup.md）所寫的公式，
 * 不呼叫 Hashing.recordHash，否則程式與文件一起漂移也不會有人發現。
 */
class RecordWriterTest {

    // ---- 文件版的驗證器 ----

    private fun documentedMaterial(doc: JsonObject): List<String> {
        fun s(path: String): String {
            var node: JsonObject? = doc
            val parts = path.split('.')
            parts.dropLast(1).forEach { node = node?.getAsJsonObject(it) }
            val leaf = node?.get(parts.last()) ?: return ""
            return if (leaf.isJsonNull) "" else leaf.asString
        }
        fun n(path: String): String {
            var node: JsonObject? = doc
            val parts = path.split('.')
            parts.dropLast(1).forEach { node = node?.getAsJsonObject(it) }
            val leaf = node?.get(parts.last()) ?: return ""
            return if (leaf.isJsonNull) "" else leaf.asLong.toString()
        }
        return listOf(
            n("seq"), s("doc_id"), s("type"),
            s("session_id"), s("tester_id"), s("project_id"), s("capture_host"),
            s("request.time"), s("response.time"),
            s("tool"), s("request.method"), s("request.url"), n("response.status"),
            s("request.raw_sha256"), s("request.body_sha256"),
            s("response.raw_sha256"), s("response.body_sha256")
        )
    }

    private fun documentedHash(doc: JsonObject, prevHash: String): String {
        val material = StringBuilder(prevHash)
        for (field in documentedMaterial(doc)) {
            material.append(field.toByteArray(Charsets.UTF_8).size).append(':').append(field)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---- 測試用具 ----

    private fun config(prefs: FakePreferences = FakePreferences(), tester: String = "zet", project: String = "acme") =
        Config(prefs).apply {
            testerId = tester
            projectId = project
            persistLocally = false
            save()
        }

    private fun exchange(writer: RecordWriter, id: Int, url: String, body: String = "hello") {
        val head = "GET /x HTTP/1.1\r\nHost: target\r\n\r\n".toByteArray()
        writer.submit(
            RequestCaptured(
                id,
                ReqData(Instant.now(), "Proxy", "GET", url, "target", 443, true, head + body.toByteArray(), head.size)
            )
        )
        val respHead = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n".toByteArray()
        writer.submit(
            ResponseCaptured(id, RespData(Instant.now(), 200, respHead + body.toByteArray(), respHead.size))
        )
    }

    private fun drain(spool: RecordingSpool, build: (RecordWriter) -> Unit) {
        val writer = RecordWriter(config(), spool, FakeLogging())
        writer.start()
        build(writer)
        writer.stop()
    }

    private fun docsOf(spool: RecordingSpool) = spool.written.map { JsonParser.parseString(it.docJson).asJsonObject }

    // ---- 測試 ----

    @Test
    fun `each record hashes to what the documented formula produces`() {
        val spool = RecordingSpool()
        drain(spool) { w -> repeat(3) { exchange(w, it, "https://target/page$it") } }

        assertEquals(3, spool.written.size)
        var prev = Hashing.GENESIS
        for (record in spool.written) {
            val doc = JsonParser.parseString(record.docJson).asJsonObject
            assertEquals(prev, doc.getAsJsonObject("integrity").get("prev_hash").asString)
            assertEquals(
                documentedHash(doc, prev),
                doc.getAsJsonObject("integrity").get("record_sha256").asString,
                "document ${doc.get("seq")} does not match the documented formula"
            )
            prev = record.recordHash
        }
    }

    @Test
    fun `seq is contiguous and continues across a reload`() {
        val prefs = FakePreferences()
        val tip = Config(prefs)
        val first = RecordingSpool(tip)
        RecordWriter(config(prefs), first, FakeLogging()).let { w ->
            w.start(); repeat(2) { exchange(w, it, "https://target/a$it") }; w.stop()
        }

        // 模擬重載：新的 spool，但鏈尾來自同一個偏好設定
        val second = RecordingSpool(Config(prefs))
        RecordWriter(config(prefs), second, FakeLogging()).let { w ->
            w.start(); exchange(w, 99, "https://target/b"); w.stop()
        }

        val seqs = (first.written + second.written).map { it.seq }
        assertEquals(listOf(1L, 2L, 3L), seqs)
        assertEquals(first.written.last().recordHash, docsOf(second).first().getAsJsonObject("integrity").get("prev_hash").asString)
    }

    @Test
    fun `the identity fields are covered by the hash`() {
        // 稽核證據的重點是「誰、在哪個案子、從哪台機器做的」。這些欄位若不入鏈，
        // 在 ES 裡改掉它們不會讓任何驗證失敗。
        //
        // 比較方式是對同一筆紀錄竄改欄位再重算 —— 若改用「跑兩次換不同 tester」，
        // 兩次的 session_id 是隨機 UUID，雜湊本來就會不同，測試會假性通過。
        val spool = RecordingSpool()
        drain(spool) { w -> exchange(w, 1, "https://target/same") }
        val record = spool.written.single()
        val doc = JsonParser.parseString(record.docJson).asJsonObject

        for (field in listOf("session_id", "tester_id", "project_id", "capture_host")) {
            val tampered = JsonParser.parseString(record.docJson).asJsonObject
            tampered.addProperty(field, "forged")
            assertNotEquals(
                record.recordHash,
                documentedHash(tampered, Hashing.GENESIS),
                "$field is not covered by the hash, so it can be rewritten in Elasticsearch undetected"
            )
        }
        assertEquals(record.recordHash, documentedHash(doc, Hashing.GENESIS))
    }

    @Test
    fun `fast mode still proves the whole message, only the body hash is skipped`() {
        // 跳過的若是整包訊息的雜湊，被排除的那些紀錄連 headers 與狀態列都無法舉證
        val spool = RecordingSpool()
        drain(spool) { w -> exchange(w, 1, "https://target/logo.png") }

        val doc = docsOf(spool).single()
        val response = doc.getAsJsonObject("response")
        assertTrue(response.get("hashes_skipped")?.asBoolean ?: false, "excluded asset should be marked")
        assertTrue(
            response.get("raw_sha256")?.asString?.length == 64,
            "raw_sha256 must still be present so the message itself stays provable"
        )
    }
}
