package io.cymetrics.eslogger

import com.google.gson.JsonParser
import io.cymetrics.eslogger.core.RecordWriter
import io.cymetrics.eslogger.fakes.FakeLogging
import io.cymetrics.eslogger.fakes.RecordingSpool
import io.cymetrics.eslogger.fakes.testConfig
import io.cymetrics.eslogger.model.ReqData
import io.cymetrics.eslogger.model.RequestCaptured
import io.cymetrics.eslogger.model.RespData
import io.cymetrics.eslogger.model.ResponseCaptured
import io.cymetrics.eslogger.model.WsCaptured
import io.cymetrics.eslogger.storage.NewRecord
import io.cymetrics.eslogger.storage.RecordSpool
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecordWriterBoundsTest {
    private class ProbeSpool(
        private val backing: RecordingSpool = RecordingSpool(),
        val fail: Boolean = false,
        val gate: CountDownLatch? = null
    ) : RecordSpool by backing {
        val entered = CountDownLatch(1)
        val attempts = Collections.synchronizedList(mutableListOf<List<NewRecord>>())
        val written: List<NewRecord> get() = backing.written

        override fun insertAll(records: List<NewRecord>) {
            entered.countDown()
            // Simulate a spool that cannot be cancelled while committing. The tests release it.
            gate?.let {
                while (it.count != 0L) {
                    try { check(it.await(5, TimeUnit.SECONDS)) } catch (_: InterruptedException) {}
                }
            }
            attempts += records.toList()
            if (fail) error("disk full")
            backing.insertAll(records)
        }
    }

    private fun ws(payload: ByteArray = "hello".toByteArray()) = WsCaptured(
        Instant.now(), "WebSocket", "wss://target/", "CLIENT_TO_SERVER", true, payload
    )

    private fun request(id: Int, bodyBytes: Int = 0): RequestCaptured {
        val head = "POST /$id HTTP/1.1\r\nHost: target\r\nContent-Type: text/plain\r\n\r\n".toByteArray()
        return RequestCaptured(id, ReqData(
            Instant.now(), "Proxy", "POST", "https://target/$id", "target", 443, true,
            head + ByteArray(bodyBytes) { 'x'.code.toByte() }, head.size
        ))
    }

    private fun response(id: Int): ResponseCaptured {
        val head = "HTTP/1.1 200 OK\r\n\r\n".toByteArray()
        return ResponseCaptured(id, RespData(Instant.now(), 200, head, head.size))
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition(), "writer did not reach the expected state")
    }

    @Test
    fun `full event count waits for capacity even when byte permits are available`() {
        val gate = CountDownLatch(1)
        val spool = ProbeSpool(gate = gate)
        val writer = RecordWriter(testConfig(), spool, FakeLogging(), RecordWriter.Limits(queueCapacity = 1, writeBatch = 1))
        val submitter = Executors.newSingleThreadExecutor()
        writer.start()
        try {
            writer.submit(ws())
            assertTrue(spool.entered.await(5, TimeUnit.SECONDS))
            writer.submit(ws())
            val started = CountDownLatch(1)
            val third = submitter.submit { started.countDown(); writer.submit(ws()) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { third.get(150, TimeUnit.MILLISECONDS) }
            gate.countDown()
            third.get(5, TimeUnit.SECONDS)
        } finally {
            gate.countDown()
            writer.stop()
            submitter.shutdownNow()
        }
        assertEquals(listOf(1L, 2L, 3L), spool.written.map { it.seq })
        assertEquals(0, writer.queueBytes())
        assertEquals(0L, writer.discardedAfterStop)
    }

    @Test
    fun `shutdown releases a submitter waiting on the event count limit`() {
        val gate = CountDownLatch(1)
        val spool = ProbeSpool(gate = gate)
        val writer = RecordWriter(testConfig(), spool, FakeLogging(), RecordWriter.Limits(queueCapacity = 1, writeBatch = 1))
        val workers = Executors.newFixedThreadPool(2)
        writer.start()
        try {
            writer.submit(ws())
            assertTrue(spool.entered.await(5, TimeUnit.SECONDS))
            writer.submit(ws())
            val waiting = workers.submit { writer.submit(ws()) }
            assertFailsWith<TimeoutException> { waiting.get(150, TimeUnit.MILLISECONDS) }
            val stopped = workers.submit { writer.stop() }
            waiting.get(5, TimeUnit.SECONDS)
            assertEquals(1L, writer.discardedAfterStop)
            gate.countDown()
            stopped.get(5, TimeUnit.SECONDS)
        } finally {
            gate.countDown()
            writer.stop()
            workers.shutdownNow()
        }
        assertEquals(2, spool.written.size)
        assertEquals(0, writer.queueDepth())
        assertEquals(0, writer.queueBytes())
    }

    @Test
    fun `pending raw byte pressure saves old requests and leaves room for responses`() {
        val spool = ProbeSpool()
        val sample = request(1, 400)
        val writer = RecordWriter(testConfig(), spool, FakeLogging(), RecordWriter.Limits(
            pendingBytes = sample.data.raw.size.toLong() * 2, pendingRequests = 100
        ))
        writer.start()
        try {
            repeat(3) { writer.submit(request(it + 1, 400)) }
            awaitCondition { writer.earlyFinalizedRequests == 1L }
            assertTrue(writer.pendingBytes() <= sample.data.raw.size.toLong() * 2)
            writer.submit(response(3))
        } finally { writer.stop() }
        val docs = spool.written.map { JsonParser.parseString(it.docJson).asJsonObject }
        assertEquals(3, docs.size)
        assertEquals(2, docs.count { it["type"].asString == "http_request_only" })
        assertEquals("https://target/3", docs.single { it["type"].asString == "http" }["request"].asJsonObject["url"].asString)
        assertEquals(0L, writer.pendingBytes())
        assertEquals(0, writer.queueBytes())
    }

    @Test
    fun `pending count pressure bounds small requests independently of raw bytes`() {
        val spool = ProbeSpool()
        val writer = RecordWriter(testConfig(), spool, FakeLogging(), RecordWriter.Limits(pendingRequests = 2))
        writer.start()
        try {
            repeat(3) { writer.submit(request(it)) }
            awaitCondition { writer.earlyFinalizedRequests == 1L }
        } finally { writer.stop() }
        assertEquals(3, spool.written.size)
        assertEquals(0L, writer.pendingBytes())
    }

    @Test
    fun `oversized request is saved immediately without evicting other pending requests`() {
        val spool = ProbeSpool()
        val writer = RecordWriter(testConfig(), spool, FakeLogging(), RecordWriter.Limits(pendingBytes = 100))
        writer.start()
        try {
            writer.submit(request(2))
            writer.submit(request(1, 500))
            awaitCondition { writer.earlyFinalizedRequests == 1L }
            assertEquals(request(2).data.raw.size.toLong(), writer.pendingBytes())
            writer.submit(response(1))
            writer.submit(response(2))
        } finally { writer.stop() }
        assertEquals(listOf("http_request_only", "http_response_only", "http"), spool.written.map {
            JsonParser.parseString(it.docJson).asJsonObject["type"].asString
        })
    }

    @Test
    fun `failed writes keep only the bounded newest records and report the loss`() {
        val spool = ProbeSpool(fail = true)
        val logging = FakeLogging()
        val config = testConfig()
        val writer = RecordWriter(config, spool, logging, RecordWriter.Limits(
            writeBatch = 1, bufferedRecords = 3, writeRetryMillis = 60_000
        ))
        writer.start()
        try {
            repeat(10) { writer.submit(ws()) }
            awaitCondition { writer.bufferOverflowDropped == 7L }
        } finally { writer.stop() }
        assertEquals(listOf(8L, 9L, 10L), spool.attempts.last().map { it.seq })
        assertEquals(10L, config.chainTipFor(writer.targetIndex).loadChainTip().first)
        assertTrue(logging.events.any { it.startsWith("CRITICAL") && "dropped" in it })
        assertTrue(logging.errors.any { "final flush failed" in it })
    }

    @Test
    fun `buffer byte cap also bounds a single oversized document and preserves consumed seq`() {
        val spool = ProbeSpool(fail = true)
        val config = testConfig()
        val writer = RecordWriter(config, spool, FakeLogging(), RecordWriter.Limits(bufferedBytes = 4096))
        writer.start()
        try {
            writer.submit(ws(ByteArray(4096) { 'x'.code.toByte() }))
            awaitCondition { writer.bufferOverflowDropped == 1L }
        } finally { writer.stop() }
        assertEquals(1L, config.chainTipFor(writer.targetIndex).loadChainTip().first)
        assertEquals(1, spool.attempts.size, "shutdown should not retain the oversized document")
        assertTrue(spool.attempts.single().single().byteSize > 4096)
    }

    @Test
    fun `an oversized record dropped during write backoff keeps older records and advances the tip`() {
        val spool = ProbeSpool(fail = true)
        val config = testConfig()
        val writer = RecordWriter(config, spool, FakeLogging(), RecordWriter.Limits(
            writeBatch = 1, bufferedBytes = 4096, writeRetryMillis = 60_000
        ))
        writer.start()
        try {
            writer.submit(ws())
            awaitCondition { spool.attempts.size == 1 }   // seq 1 失敗，下次重試在 60 秒後
            writer.submit(ws(ByteArray(8192) { 'x'.code.toByte() }))
            awaitCondition { writer.bufferOverflowDropped == 1L }
            // 還沒關閉就要越過被丟掉的 seq 2；此時當機的話，重啟不能重新發出 seq 2。
            assertEquals(2L, config.chainTipFor(writer.targetIndex).loadChainTip().first)
        } finally { writer.stop() }
        assertEquals(listOf(1L), spool.attempts.last().map { it.seq }, "the older record must survive")
    }

    @Test
    fun `spool failure while idle does not spin retrying`() {
        val spool = ProbeSpool(fail = true)
        val writer = RecordWriter(testConfig(), spool, FakeLogging(), RecordWriter.Limits(
            writeBatch = 1, writeRetryMillis = 60_000
        ))
        writer.start()
        try {
            writer.submit(ws())
            awaitCondition { spool.attempts.size == 1 }
            Thread.sleep(150)
            assertEquals(1, spool.attempts.size)
        } finally { writer.stop() }
        assertEquals(2, spool.attempts.size, "shutdown should make one final attempt")
    }

    @Test
    fun `shutdown continues all pending requests after a batch cannot be stored`() {
        val spool = ProbeSpool(fail = true)
        val config = testConfig()
        val logging = FakeLogging()
        val writer = RecordWriter(config, spool, logging, RecordWriter.Limits(
            writeBatch = 1, bufferedRecords = 3, writeRetryMillis = 60_000
        ))
        writer.start()
        repeat(10) { writer.submit(request(it)) }
        writer.stop()
        assertEquals(10L, config.chainTipFor(writer.targetIndex).loadChainTip().first, "every pending request must receive a seq")
        assertEquals(7L, writer.bufferOverflowDropped)
        assertEquals(listOf(8L, 9L, 10L), spool.attempts.last().map { it.seq })
        assertTrue(logging.errors.any { "final flush failed" in it })
        assertEquals(0L, writer.pendingBytes())
        assertEquals(0, writer.queueBytes())
    }
}
