package io.cymetrics.eslogger.core

import burp.api.montoya.logging.Logging
import io.cymetrics.eslogger.config.Config
import io.cymetrics.eslogger.integrity.Hashing
import io.cymetrics.eslogger.integrity.IntegrityV2
import io.cymetrics.eslogger.model.*
import io.cymetrics.eslogger.storage.ChainTipStore
import io.cymetrics.eslogger.storage.NewRecord
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
class RecordWriter internal constructor(
    private val config: Config,
    private val store: RecordSpool,
    private val logging: Logging,
    private val limits: Limits,
    /** 鏈尾的權威來源。Config 依目標 index 分開保存，所以換案子等於換一條鏈。 */
    val targetIndex: String = config.indexName()
) {
    constructor(config: Config, store: RecordSpool, logging: Logging, targetIndex: String = config.indexName()) :
        this(config, store, logging, Limits(), targetIndex)

    internal data class Limits(
        val queueBytes: Int = 64 * 1024 * 1024,
        val queueCapacity: Int = 20_000,
        val pendingBytes: Long = 64L * 1024 * 1024,
        val pendingRequests: Int = 20_000,
        val writeBatch: Int = 128,
        val bufferedRecords: Int = 1280,
        val bufferedBytes: Long = 32L * 1024 * 1024,
        val writeRetryMillis: Long = 1000
    ) {
        init {
            require(queueBytes > 0 && queueCapacity > 0)
            require(pendingBytes > 0 && pendingRequests > 0)
            require(writeBatch > 0 && bufferedRecords > 0 && bufferedBytes > 0)
            require(writeRetryMillis > 0)
        }
    }

    val projectId: String = config.projectId
    private val chainTip: ChainTipStore = config.chainTipFor(targetIndex)
    private val queue: BlockingQueue<CaptureEvent> = ArrayBlockingQueue(limits.queueCapacity)
    private val memoryBudget = Semaphore(limits.queueBytes)
    private val submissionLock = Any()

    private val pending = LinkedHashMap<Int, ReqData>()   // 依抵達順序釋放最舊的 request
    @Volatile private var pendingRawBytes = 0L
    @Volatile var earlyFinalizedRequests = 0L
        private set
    private var lastPendingReport = 0L
    private val sessionId = UUID.randomUUID().toString()
    // 延到 writer 執行緒第一次用到才查。macOS 的主機名稱解析不到時這裡會卡約 5 秒，
    // 放在建構子就等於卡住 Burp 載入擴充。
    private val hostName: String by lazy {
        try { InetAddress.getLocalHost().hostName } catch (_: Exception) { "unknown" }
    }

    // hash chain 狀態：只有 writer thread 碰。
    //
    // 取「持久化的鏈尾」與「spool 裡既有紀錄」兩者中較新的那個。落地模式下 spool 可能
    // 比偏好設定更新（例如偏好設定還沒被 Burp 寫回磁碟就當機），反過來則發生在寫入
    // 持續失敗時 —— 那些 seq 已經被用掉了，不能重新發號。
    private var curSeq: Long
    private var curHash: String

    /** 累積到一定量或佇列排空才寫進 SQLite，減少 commit 次數。 */
    private val writeBuffer = ArrayList<NewRecord>(limits.writeBatch)
    private var writeBufferBytes = 0L
    private var nextWriteAttemptNanos = 0L
    private var lastBufferDropReport = 0L
    private var lastBufferDroppedSeen = 0L

    @Volatile private var running = false
    private lateinit var thread: Thread
    private var lastSweep = 0L
    private var lastDroppedSeen = 0L
    private var lastDropReport = 0L
    private val discardLock = Any()
    @Volatile private var lastDiscardReport = 0L

    // init 放在所有屬性宣告之後。Kotlin 依宣告順序初始化，init 若讀到宣告在它後面的
    // 屬性，編譯器不會抱怨（只要經過一層函式呼叫），執行時才 NPE —— SettingsPanel
    // 就是這樣壞掉的。放在最後面就沒有這個可能。
    init {
        val (tipSeq, tipHash) = chainTip.loadChainTip()
        if (store.lastSeq >= tipSeq) {
            curSeq = store.lastSeq
            curHash = store.lastHash
        } else {
            curSeq = tipSeq
            curHash = tipHash
        }
    }


    /**
     * 從 Burp 的請求執行緒呼叫。
     *
     * 兩個必須同時成立的性質：
     *  - 執行中時佇列滿了就擋住 Burp（寧可慢，不漏記）—— 所以這裡會等。
     *  - 但停止之後必須立刻返回，否則卸載時 writer 已經不在消化佇列，
     *    `acquireUninterruptibly` 會永遠不返回，Burp 關不掉。
     *
     * 停止後抵達的訊息沒有分配到 seq，不會在 index 裡留下缺號，所以要自己計數並通報 ——
     * 否則就是一次「看不見的遺失」。
     */
    fun submit(e: CaptureEvent) {
        if (!running) {
            noteDiscardedAfterStop()
            return
        }
        val cost = e.sizeBytes().coerceIn(1, limits.queueBytes)
        var reserved = false
        var interrupted = false
        try {
            // 位元組與筆數都必須有空間。只有成功入列才交出配額，停止時則自行歸還。
            while (running) {
                try {
                    if (!reserved && !memoryBudget.tryAcquire(cost, SUBMIT_RETRY_MS, TimeUnit.MILLISECONDS)) continue
                    reserved = true
                    val accepted = synchronized(submissionLock) {
                        running && queue.offer(e)
                    }
                    if (accepted) {
                        reserved = false
                        return
                    }
                    if (running) Thread.sleep(SUBMIT_RETRY_MS)
                } catch (_: InterruptedException) {
                    // Burp 執行緒的中斷也不能悄悄丟訊息；離開時恢復中斷旗標。
                    interrupted = true
                }
            }
            noteDiscardedAfterStop()
        } finally {
            if (reserved) memoryBudget.release(cost)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** 關閉期間丟掉的訊息數。這是唯一不會以 seq 缺號呈現的遺失，所以必須大聲講。 */
    @Volatile var discardedAfterStop: Long = 0
        private set

    private fun noteDiscardedAfterStop() {
        synchronized(discardLock) {
            discardedAfterStop++
            val now = System.currentTimeMillis()
            if (now - lastDiscardReport < DROP_REPORT_INTERVAL_MS) return
            lastDiscardReport = now
            logging.logToError(
                "[es-logger] $discardedAfterStop message(s) arrived after the logger stopped and were " +
                    "not recorded — they have no seq, so they leave no gap. Unload or restart Burp " +
                    "while idle to avoid this."
            )
            logging.raiseCriticalEvent(
                "ES Logger: $discardedAfterStop message(s) were not recorded because the extension is shutting down"
            )
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
        // 和最後一次 offer 共用鎖，保證 drain 開始後不會再有事件入列。
        synchronized(submissionLock) { running = false }
        if (::thread.isInitialized) {
            thread.interrupt()
            try { thread.join(10_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    fun queueDepth(): Int = queue.size

    /** 佇列目前佔用的位元組，給 UI 顯示「本地吃了多少記憶體」。 */
    fun queueBytes(): Int = limits.queueBytes - memoryBudget.availablePermits()

    /** 等待 response 的原始請求另有配額，不佔用 response 入列需要的空間。 */
    fun pendingBytes(): Long = pendingRawBytes

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
            memoryBudget.release(e.sizeBytes().coerceIn(1, limits.queueBytes))
        }
    }

    private fun drainRemaining() {
        val rest = ArrayList<CaptureEvent>()
        queue.drainTo(rest)
        for (e in rest) try { consume(e) } catch (t: Throwable) {
            logging.logToError("[es-logger] could not finalize a queued event during shutdown: ${t.message}")
        }
        flushAllPending()
        flushWrites(force = true)
        reportBufferDrops(force = true)
    }

    private fun handle(e: CaptureEvent) {
        when (e) {
            is RequestCaptured -> retainRequest(e)
            is ResponseCaptured -> finalizeHttp(removePending(e.messageId), e.data)
            is WsCaptured -> finalizeWs(e)
        }
    }

    private fun removePending(messageId: Int): ReqData? = pending.remove(messageId)?.also {
        pendingRawBytes -= it.raw.size
    }

    private fun retainRequest(e: RequestCaptured) {
        // 若 Burp 重用 messageId，先保存舊請求，不能直接覆蓋。
        removePending(e.messageId)?.let { finalizeHttp(it, null) }
        val cost = e.data.raw.size.toLong()
        if (cost > limits.pendingBytes) {
            finalizeEarly(e.data)
            return
        }
        while (pending.isNotEmpty() &&
            (pendingRawBytes + cost > limits.pendingBytes || pending.size >= limits.pendingRequests)) {
            val oldest = pending.entries.first()
            val request = removePending(oldest.key)!!
            finalizeEarly(request)
        }
        pending[e.messageId] = e.data
        pendingRawBytes += cost
    }

    private fun finalizeEarly(request: ReqData) {
        finalizeHttp(request, null)
        earlyFinalizedRequests++
        val now = System.currentTimeMillis()
        if (now - lastPendingReport < DROP_REPORT_INTERVAL_MS) return
        lastPendingReport = now
        logging.logToOutput(
            "[es-logger] pending request limit reached; saved $earlyFinalizedRequests request(s) " +
                "without waiting for a response. Late responses are stored separately."
        )
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
                pendingRawBytes -= data.raw.size
                finalizeHttp(data, null)
            }
        }
    }

    private fun flushAllPending() {
        while (pending.isNotEmpty()) {
            val oldest = pending.entries.first()
            val request = removePending(oldest.key)!!
            try {
                finalizeHttp(request, null)
            } catch (t: Throwable) {
                logging.logToError("[es-logger] could not finalize a pending request during shutdown: ${t.message}")
            }
        }
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

        if (req != null) {
            // headers 只解碼一次，Content-Type 解析也共用同一份字串。
            val headText = String(req.raw, 0, req.bodyOffset, Charsets.ISO_8859_1)
            val ct = contentType(headText)
            val ex = exclusion(req.url, ct)
            val hashed = shouldHash(ex, req.bodyLength)
            // raw 一定算：它涵蓋 headers 與請求行，省掉的話被排除的那些紀錄
            // 連「當時送了什麼標頭」都無法舉證。極速模式只省另外一次 body 雜湊。
            val reqRawSha = Hashing.sha256Hex(req.raw)
            val reqBodySha = if (hashed) Hashing.sha256Hex(req.raw, req.bodyOffset, req.bodyLength) else ""

            val o = JsonObject()
            o.addProperty("time", req.ts.toString())
            o.addProperty("method", req.method)
            o.addProperty("url", req.url)
            o.addProperty("host", req.host)
            o.addProperty("port", req.port)
            o.addProperty("secure", req.secure)
            o.addProperty("headers", headText)
            addBody(o, req.raw, req.bodyOffset, req.bodyLength, reqBodySha, hashed, ex, ct)
            o.addProperty("raw_sha256", reqRawSha)
            doc.add("request", o)
        }

        if (resp != null) {
            val headText = String(resp.raw, 0, resp.bodyOffset, Charsets.ISO_8859_1)
            val ct = contentType(headText)
            // response body 用對應 request 的 URL 判斷副檔名
            val ex = exclusion(req?.url ?: "", ct)
            val hashed = shouldHash(ex, resp.bodyLength)
            val respRawSha = Hashing.sha256Hex(resp.raw)
            val respBodySha = if (hashed) Hashing.sha256Hex(resp.raw, resp.bodyOffset, resp.bodyLength) else ""

            val o = JsonObject()
            o.addProperty("time", resp.ts.toString())
            o.addProperty("status", resp.statusCode)
            o.addProperty("headers", headText)
            addBody(o, resp.raw, resp.bodyOffset, resp.bodyLength, respBodySha, hashed, ex, ct)
            o.addProperty("raw_sha256", respRawSha)
            doc.add("response", o)
        }

        writeRecord(seq, docId, prev, doc)
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

        writeRecord(seq, docId, prev, doc)
    }

    // ---------- 共用 ----------

    private fun baseDoc(ts: Instant, seq: Long, docId: String, type: String, tool: String): JsonObject {
        val doc = JsonObject()
        doc.addProperty("@timestamp", ts.toString())
        doc.addProperty("seq", seq)
        doc.addProperty("doc_id", docId)
        doc.addProperty("session_id", sessionId)
        doc.addProperty("tester_id", config.testerId)
        doc.addProperty("project_id", projectId)
        doc.addProperty("capture_host", hostName)
        doc.addProperty("type", type)
        doc.addProperty("tool", tool)
        return doc
    }

    private fun writeRecord(seq: Long, docId: String, prev: String, doc: JsonObject) {
        // v2 直接涵蓋實際儲存的完整文件，HTTP/WS 共用同一份格式，避免欄位清單漂移。
        val recordHash = IntegrityV2.attach(doc, prev)

        val json = doc.toString()
        val record = NewRecord(seq, docId, recordHash, json, json.toByteArray(Charsets.UTF_8).size)
        writeBuffer.add(record)
        writeBufferBytes += record.byteSize
        curSeq = seq
        curHash = recordHash

        if (writeBuffer.size >= limits.writeBatch || writeBufferBytes >= limits.bufferedBytes) flushWrites()
        capWriteBuffer()
    }

    /**
     * spool 失敗不可以中斷 request 的收尾；保留有界 buffer，稍後再試。
     * 即使 queue 已空也要等候，避免磁碟故障時在迴圈裡不停重試、洗滿 log。
     */
    private fun flushWrites(force: Boolean = false) {
        if (writeBuffer.isEmpty() && !force) return
        if (!force && nextWriteAttemptNanos != 0L && System.nanoTime() - nextWriteAttemptNanos < 0) return
        try {
            // 鏈尾先推進；即使 buffer 全被容量限制丟掉，也不能重用已消耗的 seq。
            chainTip.saveChainTip(curSeq, curHash)
            if (writeBuffer.isNotEmpty()) store.insertAll(writeBuffer)
            writeBuffer.clear()
            writeBufferBytes = 0
            nextWriteAttemptNanos = 0
            reportDroppedRecords()
        } catch (t: Exception) {
            nextWriteAttemptNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.writeRetryMillis)
            logging.logToError(
                if (force) "[es-logger] final flush failed — ${writeBuffer.size} record(s) were not stored: ${t.message}"
                else "[es-logger] local spool write failed; retaining records for retry: ${t.message}"
            )
        } finally {
            capWriteBuffer()
        }
    }

    /**
     * 記憶體 outbox 滿了會丟掉最舊的紀錄。那是真正的資料遺失，所以要讓使用者看得到 ——
     * 但積壓期間每批都會丟，所以限制通報頻率，否則 Event log 會被洗版。
     */
    private fun reportDroppedRecords() {
        val dropped = store.droppedCount
        if (dropped <= lastDroppedSeen) return
        val now = System.currentTimeMillis()
        if (now - lastDropReport < DROP_REPORT_INTERVAL_MS) return
        val delta = dropped - lastDroppedSeen
        lastDroppedSeen = dropped
        lastDropReport = now
        logging.logToError(
            "[es-logger] dropped $delta record(s) because the in-memory spool is full " +
                "($dropped total this session) — Elasticsearch is not keeping up, or is unreachable"
        )
        logging.raiseCriticalEvent(
            "ES Logger: dropped $delta record(s), the in-memory spool is full " +
                "($dropped total). Enable the SQLite spool to stop losing records."
        )
    }

    /**
     * 寫入持續失敗時 buffer 會一直長大（記憶體配額在 consume 的 finally 已經放掉了，
     * 沒有背壓可用），最後吃光 Burp 的 heap。超過上限就丟最舊的，並且講出來。
     *
     * 單筆就超過整個位元組預算的紀錄先單獨丟掉：照「最舊先丟」的話，它會先把其他
     * 所有紀錄擠出去，最後自己也留不住 —— 一筆大回應就清空整個 buffer。
     */
    private fun capWriteBuffer() {
        var dropped = 0
        val oversized = writeBuffer.iterator()
        while (oversized.hasNext()) {
            val record = oversized.next()
            if (record.byteSize > limits.bufferedBytes) {
                oversized.remove()
                writeBufferBytes -= record.byteSize
                dropped++
            }
        }
        var overflow = 0
        while (writeBuffer.size - overflow > limits.bufferedRecords || writeBufferBytes > limits.bufferedBytes) {
            writeBufferBytes -= writeBuffer[overflow++].byteSize
        }
        writeBuffer.subList(0, overflow).clear()
        dropped += overflow
        if (dropped == 0) return
        bufferOverflowDropped += dropped
        // 被丟掉的 seq 已經發出去了，鏈尾必須越過它們。退避期間 flushWrites 不會寫鏈尾，
        // 若此時當機，重啟後會重新發出同樣的 seq，這段遺失在 index 裡就不留缺號。
        try {
            chainTip.saveChainTip(curSeq, curHash)
        } catch (t: Exception) {
            logging.logToError("[es-logger] could not persist the chain tip after dropping records: ${t.message}")
        }
        reportBufferDrops()
    }

    private fun reportBufferDrops(force: Boolean = false) {
        val dropped = bufferOverflowDropped - lastBufferDroppedSeen
        if (dropped == 0L) return
        val now = System.currentTimeMillis()
        if (!force && now - lastBufferDropReport < DROP_REPORT_INTERVAL_MS) return
        lastBufferDropReport = now
        lastBufferDroppedSeen = bufferOverflowDropped
        logging.logToError(
            "[es-logger] the local spool keeps rejecting writes; dropped $dropped buffered record(s) " +
                "($bufferOverflowDropped total) to protect Burp's heap"
        )
        logging.raiseCriticalEvent(
            "ES Logger: dropped $dropped record(s) — the local spool is failing to accept writes"
        )
    }

    /** 因為 buffer 滿而丟掉的筆數。 */
    @Volatile var bufferOverflowDropped: Long = 0
        private set

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
     * 極速模式只省「被排除的靜態資源 body」那一次雜湊。整包訊息的 raw_sha256 一律計算 ——
     * 省掉它的話，那些紀錄連 headers 與狀態列都無法舉證，等於整筆不可驗證。
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
        const val SUBMIT_RETRY_MS = 100L
        /** 丟棄通報的最短間隔，避免積壓時洗版 Event log。 */
        const val DROP_REPORT_INTERVAL_MS = 30_000L

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
