package io.cymetrics.eslogger.core

import burp.api.montoya.logging.Logging
import io.cymetrics.eslogger.config.Config
import io.cymetrics.eslogger.integrity.Hashing
import io.cymetrics.eslogger.model.*
import io.cymetrics.eslogger.storage.ChainTipStore
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
    /** 鏈尾的權威來源。Config 依目標 index 分開保存，所以換案子等於換一條鏈。 */
    private val chainTip: ChainTipStore = config
    private val queue: BlockingQueue<CaptureEvent> = ArrayBlockingQueue(QUEUE_CAPACITY)
    private val memoryBudget = Semaphore(MAX_QUEUE_BYTES)

    private val pending = HashMap<Int, ReqData>()   // 只有 writer thread 存取
    private val sessionId = UUID.randomUUID().toString()
    private val hostName: String = try { InetAddress.getLocalHost().hostName } catch (_: Exception) { "unknown" }

    // hash chain 狀態：只有 writer thread 碰。
    //
    // 取「持久化的鏈尾」與「spool 裡既有紀錄」兩者中較新的那個。落地模式下 spool 可能
    // 比偏好設定更新（例如偏好設定還沒被 Burp 寫回磁碟就當機），反過來則發生在寫入
    // 持續失敗時 —— 那些 seq 已經被用掉了，不能重新發號。
    private var curSeq: Long
    private var curHash: String

    /** 累積到一定量或佇列排空才寫進 SQLite，減少 commit 次數。 */
    private val writeBuffer = ArrayList<NewRecord>(MAX_WRITE_BATCH)

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
        val cost = e.sizeBytes().coerceIn(1, MAX_QUEUE_BYTES)
        // 以短逾時輪詢而不是無限等待：仍在執行就繼續等（維持背壓），一旦停止就脫身。
        while (running) {
            if (memoryBudget.tryAcquire(cost, 1, TimeUnit.SECONDS)) {
                if (!running) {
                    memoryBudget.release(cost)
                    break
                }
                if (!queue.offer(e)) memoryBudget.release(cost)
                return
            }
        }
        noteDiscardedAfterStop()
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
            // raw 一定算：它涵蓋 headers 與請求行，省掉的話被排除的那些紀錄
            // 連「當時送了什麼標頭」都無法舉證。極速模式只省另外一次 body 雜湊。
            reqRawSha = Hashing.sha256Hex(req.raw)
            if (hashed) reqBodySha = Hashing.sha256Hex(req.raw, req.bodyOffset, req.bodyLength)

            val o = JsonObject()
            o.addProperty("time", req.ts.toString())
            o.addProperty("method", req.method)
            o.addProperty("url", req.url)
            o.addProperty("host", req.host)
            o.addProperty("port", req.port)
            o.addProperty("secure", req.secure)
            o.addProperty("headers", headText)
            addBody(o, req.raw, req.bodyOffset, req.bodyLength, reqBodySha, hashed, ex, contentType(headText))
            o.addProperty("raw_sha256", reqRawSha)
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
            respRawSha = Hashing.sha256Hex(resp.raw)
            if (hashed) respBodySha = Hashing.sha256Hex(resp.raw, resp.bodyOffset, resp.bodyLength)

            val o = JsonObject()
            o.addProperty("time", resp.ts.toString())
            o.addProperty("status", resp.statusCode)
            o.addProperty("headers", headText)
            addBody(o, resp.raw, resp.bodyOffset, resp.bodyLength, respBodySha, hashed, ex, ct)
            o.addProperty("raw_sha256", respRawSha)
            doc.add("response", o)
        }

        // 身分欄位必須入鏈：稽核報告的主張是「這些流量是這位測試者在這個案子產生的」，
        // 若 session / tester / project / host 不在 material 裡，在 ES 改掉它們不會破壞任何雜湊。
        val material = listOf(
            seq.toString(), docId, type,
            sessionId, config.testerId, config.projectId, hostName,
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
            seq.toString(), docId, "websocket",
            sessionId, config.testerId, config.projectId, hostName,
            e.ts.toString(), "",
            e.tool, e.direction, e.url, e.isText.toString(),
            payloadSha, "", "", ""
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
            "record_sha256 = sha256(prev_hash || for each field: len(utf8) || ':' || field); " +
                "fields = seq, doc_id, type, session_id, tester_id, project_id, capture_host, " +
                "request.time, response.time, tool, request.method, request.url, response.status, " +
                "request.raw_sha256, request.body_sha256, response.raw_sha256, response.body_sha256"
        )
        integrity.addProperty("prev_hash", prev)
        integrity.addProperty("record_sha256", recordHash)
        doc.add("integrity", integrity)

        val json = doc.toString()
        writeBuffer.add(NewRecord(seq, docId, recordHash, json, json.toByteArray(Charsets.UTF_8).size))
        curSeq = seq
        curHash = recordHash

        if (writeBuffer.size >= MAX_WRITE_BATCH) flushWrites()
        capWriteBuffer()
    }

    /** 寫入失敗時保留 buffer，下一輪重試；清空只在成功之後。 */
    private fun flushWrites() {
        if (writeBuffer.isEmpty()) return
        // 先推進鏈尾再寫入。順序反過來的話，寫入持續失敗（磁碟滿、DB 被鎖）之後重載，
        // 會從舊的鏈尾重新發出同一批 seq —— 同一個 seq 帶著不同內容出現兩次，
        // 驗證時與遭人竄改無法區分。寧可留缺號，不可重號。
        chainTip.saveChainTip(curSeq, curHash)
        store.insertAll(writeBuffer)
        writeBuffer.clear()
        reportDroppedRecords()
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
     */
    private fun capWriteBuffer() {
        if (writeBuffer.size <= MAX_BUFFERED_RECORDS) return
        val overflow = writeBuffer.size - MAX_BUFFERED_RECORDS
        repeat(overflow) { writeBuffer.removeAt(0) }
        bufferOverflowDropped += overflow
        logging.logToError(
            "[es-logger] the local spool keeps rejecting writes; dropped $overflow buffered record(s) " +
                "($bufferOverflowDropped total) to protect Burp's heap"
        )
        logging.raiseCriticalEvent(
            "ES Logger: dropped $overflow record(s) — the local spool is failing to accept writes"
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
        /** 佇列的位元組上限（不是筆數）。滿了就擋住 Burp 的執行緒。 */
        const val MAX_QUEUE_BYTES = 64 * 1024 * 1024
        const val QUEUE_CAPACITY = 20_000
        /** 一個 SQLite 交易最多累積幾筆；佇列一排空就會提前 flush。 */
        const val MAX_WRITE_BATCH = 128
        /** 本地儲存持續失敗時，buffer 最多累積幾筆。 */
        const val MAX_BUFFERED_RECORDS = MAX_WRITE_BATCH * 10
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
