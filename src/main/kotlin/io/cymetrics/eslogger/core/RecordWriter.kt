package io.cymetrics.eslogger.core

import burp.api.montoya.logging.Logging
import io.cymetrics.eslogger.config.Config
import io.cymetrics.eslogger.integrity.Hashing
import io.cymetrics.eslogger.model.*
import io.cymetrics.eslogger.storage.NewRecord
import io.cymetrics.eslogger.storage.Pending
import io.cymetrics.eslogger.storage.RecordSpool
import com.google.gson.JsonObject
import java.net.InetAddress
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 單一 writer 執行緒：
 *   1. 從 queue 取事件
 *   2. 用 messageId 配對 request / response
 *   3. 依副檔名 / Content-Type 決定是否存 body
 *   4. 計算 SHA-256 + hash chain
 *   5. 批次寫入本地 outbox
 *
 * 單執行緒保證 hash chain 的順序與一致性 —— 鏈的狀態（seq / 上一筆 hash）只有這個
 * 執行緒推進，啟動時從 store 還原。
 *
 * 記憶體：queue 的上限是**位元組**而不是筆數。單純限制筆數在遇到大量大回應時沒有意義
 * （5 萬筆 × 幾 MB 可以吃掉數 GB），所以用 [memoryBudget] 做位元組配額，
 * 滿了就讓 Burp 的執行緒等一下 —— 寧可慢，不漏記。
 */
class RecordWriter(
    private val config: Config,
    private val store: RecordSpool,
    private val logging: Logging
) {
    private val queue: BlockingQueue<CaptureEvent> = ArrayBlockingQueue(QUEUE_CAPACITY)
    private val memoryBudget = Semaphore(MAX_QUEUE_BYTES)

    private val pending = HashMap<Int, ReqData>()   // 只有 writer thread 存取
    private val sessionId = UUID.randomUUID().toString()
    private val hostName: String = try { InetAddress.getLocalHost().hostName } catch (_: Exception) { "unknown" }

    // hash chain 狀態：只有 writer thread 碰。
    private var curSeq: Long = store.lastSeq
    private var curHash: String = store.lastHash

    /** 累積到一定量或佇列排空才寫進 SQLite，減少 commit 次數。 */
    private val writeBuffer = ArrayList<NewRecord>(MAX_WRITE_BATCH)

    @Volatile private var running = false
    private lateinit var thread: Thread
    private var lastSweep = 0L

    fun submit(e: CaptureEvent) {
        val cost = e.sizeBytes().coerceIn(1, MAX_QUEUE_BYTES)
        memoryBudget.acquireUninterruptibly(cost)
        try {
            queue.put(e)
        } catch (ie: InterruptedException) {
            memoryBudget.release(cost)
            Thread.currentThread().interrupt()
        }
    }

    fun start() {
        running = true
        thread = Thread({ loop() }, "es-logger-writer").apply { isDaemon = true; start() }
        logging.logToOutput("[es-logger] writer started — session $sessionId, chain resumes at seq $curSeq")
    }

    /**
     * 停止並等 writer 自己收尾。
     *
     * 不在呼叫端的執行緒直接動 pending / writeBuffer —— 那會和還在跑的 writer thread
     * 競爭同一份 HashMap。
     */
    fun stop() {
        running = false
        if (::thread.isInitialized) {
            thread.interrupt()
            try { thread.join(10_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    fun queueDepth(): Int = queue.size

    /** 佇列目前佔用的位元組，給 UI 顯示「本地吃了多少記憶體」。 */
    fun queueBytes(): Int = MAX_QUEUE_BYTES - memoryBudget.availablePermits()

    private fun loop() {
        while (running) {
            try {
                var e = queue.poll()
                if (e == null) {
                    // 佇列空了：先把累積的寫進去，單筆流量才不會被批次邏輯延遲。
                    flushWrites()
                    e = queue.poll(2, TimeUnit.SECONDS)
                }
                if (e != null) consume(e)
                sweepIfDue()
            } catch (ie: InterruptedException) {
                break
            } catch (t: Throwable) {
                logging.logToError("[es-logger] writer error, continuing: ${t.message}")
            }
        }
        drainRemaining()
    }

    private fun consume(e: CaptureEvent) {
        try {
            handle(e)
        } finally {
            memoryBudget.release(e.sizeBytes().coerceIn(1, MAX_QUEUE_BYTES))
        }
    }

    private fun drainRemaining() {
        val rest = ArrayList<CaptureEvent>()
        queue.drainTo(rest)
        for (e in rest) try { consume(e) } catch (_: Throwable) {}
        flushAllPending()
        try {
            flushWrites()
        } catch (t: Throwable) {
            logging.logToError(
                "[es-logger] final flush failed — ${writeBuffer.size} record(s) were not stored: ${t.message}"
            )
        }
    }

    private fun handle(e: CaptureEvent) {
        when (e) {
            is RequestCaptured -> pending[e.messageId] = e.data
            is ResponseCaptured -> finalizeHttp(pending.remove(e.messageId), e.data)
            is WsCaptured -> finalizeWs(e)
        }
    }

    private fun sweepIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastSweep < 5000) return
        lastSweep = now
        if (pending.isEmpty()) return
        val cutoff = Instant.now().minusSeconds(config.requestTimeoutSeconds.toLong())
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val data = it.next().value
            if (data.ts.isBefore(cutoff)) {
                it.remove()
                finalizeHttp(data, null)
            }
        }
    }

    private fun flushAllPending() {
        if (pending.isEmpty()) return
        val all = ArrayList(pending.values)
        pending.clear()
        for (d in all) finalizeHttp(d, null)
    }

    // ---------- 落檔：HTTP ----------

    private fun finalizeHttp(req: ReqData?, resp: RespData?) {
        if (req == null && resp == null) return
        val seq = curSeq + 1
        val prev = curHash
        val docId = UUID.randomUUID().toString()

        val type = when {
            req != null && resp != null -> "http"
            req != null -> "http_request_only"
            else -> "http_response_only"
        }

        val doc = baseDoc(resp?.ts ?: req?.ts ?: Instant.now(), seq, docId, type, req?.tool ?: "unknown")

        var reqRawSha = ""
        var reqBodySha = ""
        if (req != null) {
            // headers 只解碼一次，Content-Type 解析也共用同一份字串。
            val headText = String(req.raw, 0, req.bodyOffset, Charsets.ISO_8859_1)
            val ex = exclusion(req.url, contentType(headText))
            val hashed = shouldHash(ex, req.bodyLength)
            if (hashed) {
                reqRawSha = Hashing.sha256Hex(req.raw)
                reqBodySha = Hashing.sha256Hex(req.raw, req.bodyOffset, req.bodyLength)
            }

            val o = JsonObject()
            o.addProperty("time", req.ts.toString())
            o.addProperty("method", req.method)
            o.addProperty("url", req.url)
            o.addProperty("host", req.host)
            o.addProperty("port", req.port)
            o.addProperty("secure", req.secure)
            o.addProperty("headers", headText)
            addBody(o, req.raw, req.bodyOffset, req.bodyLength, reqBodySha, hashed, ex, contentType(headText))
            if (hashed) o.addProperty("raw_sha256", reqRawSha)
            doc.add("request", o)
        }

        var respRawSha = ""
        var respBodySha = ""
        if (resp != null) {
            val headText = String(resp.raw, 0, resp.bodyOffset, Charsets.ISO_8859_1)
            val ct = contentType(headText)
            // response body 用對應 request 的 URL 判斷副檔名
            val ex = exclusion(req?.url ?: "", ct)
            val hashed = shouldHash(ex, resp.bodyLength)
            if (hashed) {
                respRawSha = Hashing.sha256Hex(resp.raw)
                respBodySha = Hashing.sha256Hex(resp.raw, resp.bodyOffset, resp.bodyLength)
            }

            val o = JsonObject()
            o.addProperty("time", resp.ts.toString())
            o.addProperty("status", resp.statusCode)
            o.addProperty("headers", headText)
            addBody(o, resp.raw, resp.bodyOffset, resp.bodyLength, respBodySha, hashed, ex, ct)
            if (hashed) o.addProperty("raw_sha256", respRawSha)
            doc.add("response", o)
        }

        val material = listOf(
            seq.toString(), docId, type,
            req?.ts?.toString() ?: "", resp?.ts?.toString() ?: "",
            req?.tool ?: "", req?.method ?: "", req?.url ?: "",
            resp?.statusCode?.toString() ?: "",
            reqRawSha, reqBodySha, respRawSha, respBodySha
        )
        writeRecord(seq, docId, prev, material, doc)
    }

    // ---------- 落檔：WebSocket ----------

    private fun finalizeWs(e: WsCaptured) {
        val seq = curSeq + 1
        val prev = curHash
        val docId = UUID.randomUUID().toString()
        val payloadSha = Hashing.sha256Hex(e.payload)

        val doc = baseDoc(e.ts, seq, docId, "websocket", e.tool)

        val o = JsonObject()
        o.addProperty("url", e.url)
        o.addProperty("direction", e.direction)
        o.addProperty("is_text", e.isText)
        addBody(
            o, e.payload, 0, e.payload.size, payloadSha, true, Exclusion.NONE,
            if (e.isText) "text/plain" else "application/octet-stream"
        )
        doc.add("websocket", o)

        val material = listOf(
            seq.toString(), docId, "websocket", e.ts.toString(), "",
            e.tool, e.direction, e.url, "", payloadSha, "", "", ""
        )
        writeRecord(seq, docId, prev, material, doc)
    }

    // ---------- 共用 ----------

    private fun baseDoc(ts: Instant, seq: Long, docId: String, type: String, tool: String): JsonObject {
        val doc = JsonObject()
        doc.addProperty("@timestamp", ts.toString())
        doc.addProperty("seq", seq)
        doc.addProperty("doc_id", docId)
        doc.addProperty("session_id", sessionId)
        doc.addProperty("tester_id", config.testerId)
        doc.addProperty("project_id", config.projectId)
        doc.addProperty("capture_host", hostName)
        doc.addProperty("type", type)
        doc.addProperty("tool", tool)
        return doc
    }

    private fun writeRecord(seq: Long, docId: String, prev: String, material: List<String>, doc: JsonObject) {
        val recordHash = Hashing.recordHash(material, prev)
        val integrity = JsonObject()
        integrity.addProperty("algo", "sha256")
        integrity.addProperty(
            "scheme",
            "record_sha256 = sha256(prev_hash || for each field: len(utf8) || ':' || field)"
        )
        integrity.addProperty("prev_hash", prev)
        integrity.addProperty("record_sha256", recordHash)
        doc.add("integrity", integrity)

        writeBuffer.add(NewRecord(seq, docId, recordHash, doc.toString()))
        curSeq = seq
        curHash = recordHash

        if (writeBuffer.size >= MAX_WRITE_BATCH) flushWrites()
    }

    /** 寫入失敗時保留 buffer，下一輪重試；清空只在成功之後。 */
    private fun flushWrites() {
        if (writeBuffer.isEmpty()) return
        store.insertAll(writeBuffer)
        writeBuffer.clear()
    }

    private fun contentType(headText: String): String =
        CONTENT_TYPE_RE.find(headText)?.groupValues?.get(1)?.trim()?.lowercase() ?: ""

    /**
     * 決定要不要存 body：
     *   - storeBodies 關閉 -> 一律不存，但仍記長度 + hash
     *   - URL 副檔名在排除清單，或 Content-Type 屬於排除類型 -> 不存
     *   - body 過大 -> 存截斷片段 + 標記 truncated，並保留完整長度 + 完整 hash
     * 不論存不存，body_len / body_sha256 一定寫入，以證明「當時收到過這份內容」。
     *
     * body 以 raw + offset 傳入，文字型態可以直接就地解碼，不需要先切一份陣列出來。
     * bodySha 由呼叫端算好傳進來（原本這裡會對同一段 body 再雜湊一次）。
     */
    private fun addBody(
        o: JsonObject,
        raw: ByteArray,
        offset: Int,
        length: Int,
        bodySha: String,
        hashed: Boolean,
        exclusion: Exclusion,
        contentType: String
    ) {
        o.addProperty("body_len", length)
        // 極速模式下這段完全沒算雜湊，明寫出來，不要讓缺少的欄位被當成沒發生。
        if (hashed) o.addProperty("body_sha256", bodySha) else o.addProperty("hashes_skipped", true)

        if (!config.storeBodies || exclusion != Exclusion.NONE) {
            o.addProperty("body_stored", false)
            o.addProperty(
                "body_skip_reason",
                when {
                    !config.storeBodies -> "store_bodies_disabled"
                    exclusion == Exclusion.EXTENSION -> "excluded_extension"
                    else -> "excluded_content_type"
                }
            )
            return
        }
        if (length == 0) {
            o.addProperty("body_stored", true)
            o.addProperty("body", "")
            o.addProperty("body_encoding", "text")
            return
        }

        val keep = minOf(length, config.maxStoredBodyBytes)
        o.addProperty("body_stored", true)
        o.addProperty("body_truncated", keep < length)

        if (looksTextual(contentType)) {
            o.addProperty("body", String(raw, offset, keep, Charsets.UTF_8))
            o.addProperty("body_encoding", "text")
        } else {
            // base64 需要連續陣列，這份複製的大小被 maxStoredBodyBytes 夾住。
            o.addProperty("body_b64", Base64.getEncoder().encodeToString(raw.copyOfRange(offset, offset + keep)))
            o.addProperty("body_encoding", "base64")
        }
    }

    /** body 被排除的原因，算一次就好（原本 addBody 會再判一次）。 */
    private enum class Exclusion { NONE, EXTENSION, CONTENT_TYPE }

    private fun exclusion(url: String, ct: String): Exclusion = when {
        isExcludedExtension(url) -> Exclusion.EXTENSION
        isExcludedContentType(ct) -> Exclusion.CONTENT_TYPE
        else -> Exclusion.NONE
    }

    /**
     * 極速模式只對「本來就不會保存的靜態資源 body」生效：連 raw 與 body 雜湊都省掉。
     *
     * 刻意不套用到 storeBodies=false —— 那個模式的全部價值就是雜湊，
     * 連雜湊都省掉等於什麼都沒記。空 body 也照算，反正不花錢。
     */
    private fun shouldHash(exclusion: Exclusion, bodyLength: Int): Boolean =
        !config.fastMode || exclusion == Exclusion.NONE || bodyLength == 0

    private fun isExcludedExtension(url: String): Boolean {
        if (url.isBlank()) return false
        val path = url.substringBefore('?').substringBefore('#')
        val seg = path.substringAfterLast('/')
        val dot = seg.lastIndexOf('.')
        if (dot < 0 || dot == seg.length - 1) return false
        return seg.substring(dot + 1).lowercase() in config.excludedExtensions
    }

    private fun isExcludedContentType(ct: String): Boolean {
        if (ct.isBlank()) return false
        for ((mime, ext) in MIME_MARKERS) {
            if (ct.contains(mime) && ext in config.excludedExtensions) return true
        }
        return false
    }

    private fun looksTextual(ct: String): Boolean {
        if (ct.isBlank()) return true // 不確定就當文字（grep 友善）；真的亂碼時 raw_sha256 仍可驗證
        return ct.startsWith("text/") ||
            ct.contains("json") || ct.contains("xml") ||
            ct.contains("x-www-form-urlencoded") ||
            ct.contains("javascript") || ct.contains("csv") ||
            ct.contains("html") || ct.contains("graphql")
    }

    private companion object {
        /** 佇列的位元組上限（不是筆數）。滿了就擋住 Burp 的執行緒。 */
        const val MAX_QUEUE_BYTES = 64 * 1024 * 1024
        const val QUEUE_CAPACITY = 20_000
        /** 一個 SQLite 交易最多累積幾筆；佇列一排空就會提前 flush。 */
        const val MAX_WRITE_BATCH = 128

        /** 編譯一次就好：原本每一筆訊息都重新 new 一個 Regex。 */
        val CONTENT_TYPE_RE = Regex("(?im)^content-type:\\s*([^\\r\\n]+)")

        /** 把排除副檔名對應到常見 MIME（處理無副檔名的靜態資源 URL）。原本每次呼叫都重建這張表。 */
        val MIME_MARKERS = listOf(
            "javascript" to "js", "ecmascript" to "js",
            "image/gif" to "gif", "image/jpeg" to "jpg", "image/png" to "png",
            "image/x-icon" to "ico", "image/vnd.microsoft.icon" to "ico",
            "text/css" to "css", "image/svg" to "svg",
            "font/woff2" to "woff2", "font/woff" to "woff", "font/ttf" to "ttf",
            "application/font-woff" to "woff", "font/sfnt" to "ttf"
        )
    }
}
