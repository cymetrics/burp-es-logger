package io.cymetrics.eslogger.upload

import burp.api.montoya.logging.Logging
import io.cymetrics.eslogger.config.Config
import io.cymetrics.eslogger.storage.NewRecord
import io.cymetrics.eslogger.storage.Pending
import io.cymetrics.eslogger.storage.RecordSpool
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * 背景上傳執行緒。
 *
 * 關鍵設計：
 *   - 用 JDK HttpClient（不是 Burp 的 sendRequest），所以上傳流量不會被自己記錄，
 *     也不會把 API key 寫進 log。
 *   - _bulk 使用 "create" + 自訂 _id (我們產生的 UUID)。重送時：
 *       201 = 新建成功；409 = 已存在（等同成功）-> 兩者都標記 uploaded。
 *     因此重送絕不會產生重複資料。
 *   - _bulk 整體 HTTP 200 不代表每筆都成功，一定要逐筆檢查 items[].create.status。
 *   - 傳輸層失敗採 exponential backoff。
 */
class ElasticUploader(
    private val config: Config,
    private val store: RecordSpool,
    private val logging: Logging
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @Volatile private var running = false
    private lateinit var thread: Thread

    @Volatile var lastError: String = ""
        private set
    @Volatile var lastUploadAt: Instant? = null
        private set
    @Volatile var lastUploadCount: Int = 0
        private set
    /** 本 session 累計成功上傳的筆數。本地不留副本後，這是「總共記了多少」的來源。 */
    @Volatile var uploadedTotal: Long = 0
        private set

    /** 本 session 因為 ES 永久拒絕而放棄的筆數。這些 seq 會在 index 裡缺號。 */
    @Volatile var rejectedTotal: Long = 0
        private set

    /** 每筆被拒絕的次數。uploader thread 與卸載執行緒都會碰，所以用並行的 map。 */
    private val rejectAttempts = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    /** 整批被拒時暫時縮小的批次量，用來二分逼近出問題的那一筆；成功後歸零。 */
    @Volatile private var batchLimitOverride = 0

    fun start() {
        running = true
        thread = Thread({ loop() }, "es-logger-uploader").apply { isDaemon = true; start() }
    }

    /**
     * 卸載 / 重載前的最後努力：在時限內把 spool 清空。
     *
     * 沒有這一步的話，純記憶體模式每次重載 extension 都會丟掉尚未上傳的紀錄 ——
     * 開發時反覆重載尤其明顯。回傳仍未送出的筆數。
     */
    fun drainBeforeExit(timeoutMillis: Long): Long {
        if (config.esEndpoint.isBlank() || config.esApiKey.isBlank()) return store.pendingCount
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (store.pendingCount > 0 && System.currentTimeMillis() < deadline) {
            try {
                if (!flushOnce()) break
            } catch (t: Throwable) {
                logging.logToError("[es-logger] final upload attempt failed: ${t.message}")
                break
            }
        }
        return store.pendingCount
    }

    fun stop() {
        running = false
        if (::thread.isInitialized) thread.interrupt()
    }

    private fun loop() {
        var backoff = 1000L
        while (running) {
            try {
                if (!config.uploadEnabled || config.esEndpoint.isBlank() || config.esApiKey.isBlank()) {
                    Thread.sleep(config.uploadIntervalSeconds * 1000L)
                    continue
                }
                val uploadedAny = flushOnce()
                backoff = 1000L
                if (!uploadedAny) Thread.sleep(config.uploadIntervalSeconds * 1000L)
            } catch (ie: InterruptedException) {
                break
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                logging.logToError("[es-logger] upload failed, retrying in ${backoff}ms: $lastError")
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    /**
     * 嘗試送出一批。回傳 true 表示這輪有送出資料（可能還有更多待送）。
     *
     * 呼叫端的 loop 在回傳 true 時不會 sleep，所以積壓會被連續的 _bulk 排掉，
     * `uploadIntervalSeconds` 只是閒置時的輪詢週期，不是吞吐上限。
     */
    fun flushOnce(): Boolean {
        val limit = if (batchLimitOverride > 0) minOf(batchLimitOverride, config.uploadBatchSize)
        else config.uploadBatchSize
        val pending = store.pendingBatch(limit)
        if (pending.isEmpty()) return false

        // 除了筆數，還要卡位元組：單筆 body 上限預設 2 MB，500 筆湊起來可以是數百 MB，
        // 一次組進 StringBuilder 會吃爆 Burp 的 heap，ES 那端也會以 413 回絕，
        // 而且同一批每輪重送 → 永久卡住。所以在筆數上限之內再切一刀。
        var count = 0
        var bytes = 0L
        for (p in pending) {
            val size = p.docJson.length.toLong() + BULK_ACTION_OVERHEAD
            if (count > 0 && bytes + size > MAX_BULK_BYTES) break
            count++
            bytes += size
        }
        // count 至少是 1：單筆就超標也要送，否則這筆會永遠擋住後面的佇列。
        val batch = pending.take(count)

        val index = config.indexName()
        val sb = StringBuilder()
        for (p in batch) {
            // 動作行：對 data-stream 以外的一般 index 可指定 _id，重送才具冪等性
            sb.append("{\"create\":{\"_index\":\"").append(index)
                .append("\",\"_id\":\"").append(p.docId).append("\"}}\n")
            sb.append(p.docJson).append('\n')
        }

        val req = HttpRequest.newBuilder()
            .uri(URI.create("${config.esEndpoint}/_bulk"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "ApiKey ${config.esApiKey}")
            .header("Content-Type", "application/x-ndjson")
            .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
            .build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            val detail = "HTTP ${resp.statusCode()} (${batch.size} docs / ${bytes / 1024} KB): " +
                resp.body().take(300)
            when (FailurePolicy.classifyHttp(resp.statusCode(), batch.size)) {
                FailurePolicy.BatchOutcome.RETRY_LATER -> throw RuntimeException(detail)

                FailurePolicy.BatchOutcome.SPLIT_BATCH -> {
                    batchLimitOverride = batch.size / 2
                    logging.logToError(
                        "[es-logger] Elasticsearch rejected the whole batch — retrying with " +
                            "$batchLimitOverride document(s) to isolate the offending record. $detail"
                    )
                    throw RuntimeException(detail)
                }

                FailurePolicy.BatchOutcome.DROP_BATCH -> {
                    dropPermanently(batch, detail)
                    batchLimitOverride = 0
                    return true
                }
            }
        }
        batchLimitOverride = 0

        val outcome = BulkResponse.parse(resp.body(), batch)
        outcome.accepted.forEach { rejectAttempts.remove(it.seq) }
        outcome.rejected.forEach { (row, reason) ->
            val attempts = rejectAttempts.merge(row.seq, 1, Int::plus) ?: 1
            if (FailurePolicy.shouldDropDocument(attempts)) {
                dropPermanently(listOf(row), "rejected $attempts times — $reason")
            } else {
                logging.logToError(
                    "[es-logger] document seq ${row.seq} rejected (attempt $attempts of " +
                        "${FailurePolicy.MAX_DOC_ATTEMPTS}): $reason"
                )
            }
        }
        val succeeded = outcome.accepted
        if (succeeded.isNotEmpty()) {
            // 確認進了 ES 就把本地那幾列刪掉：本地只當 outbox，不留第二份。
            store.purge(succeeded.map { it.seq })
            uploadedTotal += succeeded.size
        }

        lastUploadAt = Instant.now()
        lastUploadCount = succeeded.size
        lastError = if (succeeded.size == batch.size) "" else
            "${batch.size - succeeded.size}/${batch.size} documents rejected — see the Extensions log"

        if (succeeded.size < batch.size) {
            logging.logToError(
                "[es-logger] ${batch.size - succeeded.size} of ${batch.size} documents were rejected, retrying next round"
            )
        }
        return true
    }

    private companion object {
        /** 單一 _bulk 請求的位元組上限。ES 預設 http.max_content_length 是 100 MB，留足餘裕。 */
        const val MAX_BULK_BYTES = 8L * 1024 * 1024
        /** 每筆多出來的 action 行（index 名 + _id）概估。 */
        const val BULK_ACTION_OVERHEAD = 120L
    }

    /**
     * 放棄這幾筆：從本地刪掉，讓佇列能往前走。
     *
     * 這是整個擴充唯一會「主動丟掉紀錄」的地方，所以講得非常大聲 —— Extensions log
     * 寫明是哪幾個 seq、為什麼，UI 的狀態列也會顯示。缺號本身是刻意留下的證據，
     * 驗證時看得出來這段不是被人刪掉的。
     */
    private fun dropPermanently(rows: List<Pending>, reason: String) {
        val seqs = rows.map { it.seq }
        store.purge(seqs)
        seqs.forEach { rejectAttempts.remove(it) }
        rejectedTotal += rows.size
        lastError = "${rows.size} record(s) permanently rejected by Elasticsearch (seq ${seqs.joinToString()}) — $reason"
        logging.logToError(
            "[es-logger] GIVING UP on ${rows.size} record(s): seq ${seqs.joinToString()}. $reason\n" +
                "[es-logger] These records are gone from the local spool and will never reach Elasticsearch. " +
                "The resulting gap in seq is intentional and detectable during verification."
        )
    }

    /** 測試連線的結果。文案留給 UI 處理，這裡只回報狀態與語言無關的細節。 */
    enum class ProbeStatus { OK_AUTHENTICATED, OK_CLUSTER, UNAUTHORIZED, FORBIDDEN, UNREACHABLE, OTHER }

    data class TestResult(val status: ProbeStatus, val httpCode: Int, val detail: String)

    /**
     * 給 UI「測試連線」按鈕用。
     *
     * 注意：Burp 端用的是 append-only key（cluster 權限為空），所以 `GET /` 會回 403。
     * 403 代表「憑證有效但沒有讀取權限」，是預期行為，不能當成連線失敗。
     * 因此先用 `_security/_authenticate`（任何有效憑證都可呼叫、不需額外權限）驗 key，
     * 失敗才退回 `GET /`。
     */
    fun testConnection(): TestResult {
        val base = config.esEndpoint.trimEnd('/')

        probe("$base/_security/_authenticate")?.let { (code, body) ->
            when (code) {
                200 -> return TestResult(ProbeStatus.OK_AUTHENTICATED, 200, describeIdentity(body))
                401 -> return TestResult(ProbeStatus.UNAUTHORIZED, 401, "")
            }
        }

        val fallback = probe("$base/")
            ?: return TestResult(ProbeStatus.UNREACHABLE, 0, base)
        val (code, body) = fallback
        return when (code) {
            200 -> TestResult(ProbeStatus.OK_CLUSTER, 200, clusterName(body))
            401 -> TestResult(ProbeStatus.UNAUTHORIZED, 401, "")
            403 -> TestResult(ProbeStatus.FORBIDDEN, 403, "")
            else -> TestResult(ProbeStatus.OTHER, code, body.take(200))
        }
    }

    /** `GET /` 的回應只取 cluster 名稱。 */
    private fun clusterName(body: String): String = try {
        JsonParser.parseString(body).asJsonObject.get("cluster_name")?.asString?.let { "cluster: $it" } ?: ""
    } catch (t: Throwable) {
        ""
    }

    /**
     * 從 `_security/_authenticate` 的回應裡挑出可辨識身分的最小欄位。
     *
     * 回應原文含 full_name / email 等個資，狀態列會被截圖進報告或 demo，
     * 所以只取 API key 名稱與 realm，其餘不顯示。回傳值與語言無關。
     */
    private fun describeIdentity(body: String): String = try {
        val root = JsonParser.parseString(body).asJsonObject
        val keyName = root.getAsJsonObject("api_key")?.get("name")?.asString
        val realm = root.getAsJsonObject("authentication_realm")?.get("name")?.asString
        when {
            keyName != null && realm != null -> "API key \"$keyName\" (realm $realm)"
            keyName != null -> "API key \"$keyName\""
            realm != null -> "realm $realm"
            else -> "-"
        }
    } catch (t: Throwable) {
        "-"
    }

    /** 送一個 GET，回傳 (status, body)；連不上回 null。 */
    private fun probe(url: String): Pair<Int, String>? = try {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "ApiKey ${config.esApiKey}")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        resp.statusCode() to resp.body()
    } catch (t: Throwable) {
        null
    }
}
