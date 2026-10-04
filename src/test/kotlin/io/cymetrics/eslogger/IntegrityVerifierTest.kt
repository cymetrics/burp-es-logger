package io.cymetrics.eslogger

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.cymetrics.eslogger.core.RecordWriter
import io.cymetrics.eslogger.fakes.FakeLogging
import io.cymetrics.eslogger.fakes.RecordingSpool
import io.cymetrics.eslogger.fakes.testConfig
import io.cymetrics.eslogger.integrity.IntegrityV2
import io.cymetrics.eslogger.integrity.Hashing
import io.cymetrics.eslogger.model.ReqData
import io.cymetrics.eslogger.model.RequestCaptured
import io.cymetrics.eslogger.model.RespData
import io.cymetrics.eslogger.model.ResponseCaptured
import io.cymetrics.eslogger.model.WsCaptured
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercise the shipped Python program against real writer output, across the language boundary. */
class IntegrityVerifierTest {
    private fun verify(documents: List<JsonObject>): Pair<Int, String> {
        val file = Files.createTempFile("eslogger-integrity-", ".ndjson")
        try {
            Files.writeString(file, documents.joinToString("\n", postfix = "\n"))
            val process = ProcessBuilder(
                "python3", "tools/verify_chain.py", "--file", file.toString(), "--strict", "--require-v2"
            ).redirectErrorStream(true).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Python verifier did not finish within 30 seconds")
            }
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun capturedDocuments(): List<JsonObject> {
        val spool = RecordingSpool()
        val config = testConfig(tester = "測試😀").apply { maxStoredBodyBytes = 5 }
        val writer = RecordWriter(config, spool, FakeLogging())
        val now = Instant.now()
        val requestHead = "POST / HTTP/1.1\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray()
        val responseHead = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n".toByteArray()
        fun request(id: Int, url: String) = writer.submit(RequestCaptured(id, ReqData(
            now, "Proxy", "POST", url, "target", 443, true,
            requestHead + byteArrayOf(0, 1, 2, -1, -2, -3), requestHead.size
        )))
        fun response(id: Int) = writer.submit(ResponseCaptured(id, RespData(
            now, 200, responseHead + "中😀文字".toByteArray(), responseHead.size
        )))
        writer.start()
        request(1, "https://target/path?q=😀")
        response(1)
        request(2, "https://target/image.png")
        response(2)
        request(3, "https://target/request-only")
        response(99)
        writer.submit(WsCaptured(now, "WebSocket", "wss://target/😀", "CLIENT_TO_SERVER", true, "中😀文字".toByteArray()))
        writer.submit(WsCaptured(now, "WebSocket", "wss://target/", "SERVER_TO_CLIENT", false, byteArrayOf(0, -1, 2, 3, 4, 5)))
        writer.stop()
        return spool.written.map { JsonParser.parseString(it.docJson).asJsonObject }
    }

    @Test
    fun `writer output verifies for HTTP variants websocket binary truncated and unicode bodies`() {
        val documents = capturedDocuments()
        assertEquals(6, documents.size)
        assertEquals(setOf("http", "http_request_only", "http_response_only", "websocket"),
            documents.map { it["type"].asString }.toSet())
        assertTrue(documents.first().getAsJsonObject("request")["body_truncated"].asBoolean)
        assertEquals("base64", documents.first().getAsJsonObject("request")["body_encoding"].asString)
        val (status, output) = verify(documents)
        assertEquals(0, status, output)
    }

    @Test
    fun `actual writer bodies and headers cannot be changed without verifier failure`() {
        val original = capturedDocuments()
        for ((section, key) in listOf("request" to "body_b64", "request" to "headers", "response" to "body")) {
            val documents = original.map { it.deepCopy() }
            documents.first().getAsJsonObject(section).addProperty(key, "forged")
            val (status, output) = verify(documents)
            assertEquals(1, status, "$section.$key: $output")
            assertTrue(output.contains("payload"), output)
        }
    }

    @Test
    fun `canonical vector matches Python for numeric forms unicode ordering and surrogate code units`() {
        val fixture = Path.of("tools/fixtures/canonical-v2.json")
        val doc = JsonParser.parseString(Files.readString(fixture)).asJsonObject
        val expected = Files.readString(Path.of("tools/fixtures/canonical-v2.sha256")).trim()
        assertEquals(expected, IntegrityV2.payloadHash(doc))
    }

    @Test
    fun `unpaired metadata surrogates are normalized before UTF8 transport and hashing`() {
        val doc = JsonObject().apply {
            addProperty("seq", 1)
            addProperty("doc_id", "unicode-transport-test")
            addProperty("type", "http_request_only")
            addProperty("tester_id", "a\uD800b\uDC00c😀")
        }
        IntegrityV2.attach(doc, Hashing.GENESIS)
        assertEquals("a\uFFFDb\uFFFDc😀", doc["tester_id"].asString)
        // The CLI receives a real UTF-8 file, rather than an in-memory JSON tree.
        val (status, output) = verify(listOf(doc))
        assertEquals(0, status, output)
    }
}
