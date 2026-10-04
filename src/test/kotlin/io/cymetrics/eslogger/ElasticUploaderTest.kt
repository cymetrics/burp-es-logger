package io.cymetrics.eslogger

import io.cymetrics.eslogger.fakes.FakeLogging
import io.cymetrics.eslogger.fakes.testConfig
import io.cymetrics.eslogger.storage.MemorySpool
import io.cymetrics.eslogger.storage.NewRecord
import io.cymetrics.eslogger.upload.ElasticUploader
import java.lang.reflect.Proxy
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ElasticUploaderTest {
    private class StubHttp(var body: String) : HttpClient() {
        var sentBody = ""
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects() = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters() = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version() = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()
        @Suppress("UNCHECKED_CAST")
        override fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            val bytes = java.io.ByteArrayOutputStream()
            request.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
                override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
                override fun onNext(item: ByteBuffer) {
                    val part = ByteArray(item.remaining())
                    item.get(part)
                    bytes.write(part)
                }
                override fun onError(error: Throwable) { throw AssertionError(error) }
                override fun onComplete() { sentBody = bytes.toString(Charsets.UTF_8) }
            })
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpResponse::class.java)) { _, method, _ ->
                when (method.name) { "statusCode" -> 200; "body" -> body; else -> null }
            } as HttpResponse<T>
        }
        override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> =
            error("not used")
        override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>, push: HttpResponse.PushPromiseHandler<T>): CompletableFuture<HttpResponse<T>> =
            error("not used")
    }

    private fun item(status: Int) = """{"create":{"status":$status}}"""
    private fun response(vararg statuses: Int) = """{"items":[${statuses.joinToString(",") { item(it) }}]}"""
    private fun spool(count: Int = 1) = MemorySpool().apply {
        insertAll((1..count).map { NewRecord(it.toLong(), "doc-$it", "hash", "{}", 2) })
    }
    private fun uploader(spool: MemorySpool, http: StubHttp) = ElasticUploader(
        testConfig().apply { esEndpoint = "https://unused.invalid"; esApiKey = "test-only" }, spool, FakeLogging(), http
    )

    @Test
    fun `saving another project cannot redirect already captured records`() {
        val config = testConfig(project = "acme").apply { esEndpoint = "https://unused.invalid"; esApiKey = "test-only" }
        val http = StubHttp(response(201))
        val uploader = ElasticUploader(config, spool(), FakeLogging(), http)
        config.projectId = "beta"
        config.indexPrefix = "other"
        uploader.flushOnce()
        val action = com.google.gson.JsonParser.parseString(http.sentBody.lineSequence().first()).asJsonObject
        assertEquals("burp-log-acme", action.getAsJsonObject("create")["_index"].asString)
    }

    @Test
    fun `temporary item failures remain pending beyond the permanent attempt limit`() {
        for (status in listOf(401, 403, 404, 408, 413, 429, 500, 503)) {
            val spool = spool()
            val uploader = uploader(spool, StubHttp(response(status)))
            repeat(5) { assertFails { uploader.flushOnce() } }
            assertEquals(1L, spool.pendingCount, "status $status must remain retryable")
            assertEquals(0L, uploader.rejectedTotal)
            assertEquals(0L, uploader.uploadedTotal)
        }
    }

    @Test
    fun `malformed success response retains the only local copy`() {
        for (body in listOf("<html>login</html>", "{}", "{\"errors\":true}", "{\"items\":[]}")) {
            val spool = spool()
            val uploader = uploader(spool, StubHttp(body))
            assertFails { uploader.flushOnce() }
            assertEquals(1L, spool.pendingCount)
            assertEquals(0L, uploader.uploadedTotal)
        }
    }

    @Test
    fun `mixed batch purges confirmed items and retries others with same document id`() {
        val spool = spool(3)
        val http = StubHttp(response(201, 429, 409))
        val uploader = uploader(spool, http)
        assertFails { uploader.flushOnce() }
        assertEquals(listOf("doc-2"), spool.pendingBatch(10).map { it.docId })
        assertEquals(2L, uploader.uploadedTotal)
        http.body = response(201)
        assertEquals(ElasticUploader.FlushResult.SENT, uploader.flushOnce())
        assertEquals(0L, spool.pendingCount)
        assertEquals(3L, uploader.uploadedTotal)
    }

    @Test
    fun `temporary failures do not consume permanent document retry attempts`() {
        val spool = spool()
        val http = StubHttp(response(429))
        val uploader = uploader(spool, http)
        repeat(4) { assertFails { uploader.flushOnce() } }
        http.body = response(400)
        repeat(2) {
            assertFails { uploader.flushOnce() }
            assertEquals(1L, spool.pendingCount)
        }
        uploader.flushOnce()
        assertEquals(0L, spool.pendingCount)
        assertEquals(1L, uploader.rejectedTotal)
        assertEquals(0L, uploader.uploadedTotal)
    }
}
