package io.cymetrics.eslogger.config

import burp.api.montoya.persistence.Preferences
import io.cymetrics.eslogger.integrity.Hashing
import io.cymetrics.eslogger.storage.ChainTipStore

/**
 * 所有設定值。讀寫都透過 Burp 的 Preferences（跨重啟保存）。
 *
 * 注意：API key 會以明文存在 Burp 的使用者偏好設定中。測試機請做磁碟加密，
 * 案件結束後清除。
 */
class Config(private val prefs: Preferences) {

    // --- Elasticsearch ---
    @Volatile var esEndpoint: String = get("es.endpoint", "")              // 例如 https://xxx.es.region.aws.elastic.cloud
    @Volatile var esApiKey: String = get("es.apikey", "")                  // Kibana 產生的 encoded api key
    @Volatile var indexPrefix: String = get("es.indexPrefix", DEFAULT_INDEX_PREFIX)  // 實際 index = <prefix>-<project>

    // --- 稽核識別 ---
    @Volatile var testerId: String = get("audit.tester", "")
    @Volatile var projectId: String = get("audit.project", "default")

    // --- 擷取範圍 / body 規則 ---
    // 這些副檔名只存 metadata + hash，不存 body
    @Volatile var excludedExtensions: Set<String> =
        get("capture.excludedExt", "js,gif,jpg,jpeg,png,ico,css,woff,woff2,ttf,svg")
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    @Volatile var captureWebSockets: Boolean = getBool("capture.ws", true)

    // 極速模式：被排除的靜態資源 body 連雜湊都不算（raw 與 body 雜湊一起省）。
    // 代價是那些訊息事後無法舉證，文件會標 hashes_skipped。
    // 刻意不套用到 storeBodies=false 的情況 —— 那個模式的價值就是雜湊本身。
    // 預設開啟：排除清單裡的是客戶自己的靜態資源，雜湊它沒有舉證價值。
    @Volatile var fastMode: Boolean = getBool("capture.fastMode", true)
    @Volatile var storeBodies: Boolean = getBool("capture.storeBodies", true)

    // 單一 body 超過此大小只存截斷片段 + 完整 hash + 完整長度（避免 ES 文件過大）
    @Volatile var maxStoredBodyBytes: Int = getInt("capture.maxBodyBytes", 2 * 1024 * 1024)

    // 沒有收到 response 的 request，超過這個秒數就以 "request-only" 落檔
    @Volatile var requestTimeoutSeconds: Int = getInt("capture.reqTimeout", 120)

    // --- 上傳行為 ---
    @Volatile var uploadIntervalSeconds: Int = getInt("upload.intervalSec", 15)
    @Volatile var uploadBatchSize: Int = getInt("upload.batchSize", 500)
    @Volatile var uploadEnabled: Boolean = getBool("upload.enabled", true)

    // --- 介面 ---
    @Volatile var uiLang: String = get("ui.lang", "zh")

    // --- 本地暫存 ---
    // 預設不落地：待上傳佇列只放在記憶體，Burp 關閉或超過上限時未送出的會遺失（seq 跳號可辨識）。
    // 打開才會寫 SQLite，積壓多少留多少。切換後需重載 extension 才生效。
    @Volatile var persistLocally: Boolean = getBool("storage.persistLocal", false)
    @Volatile var dbPath: String = get("storage.dbPath", defaultDbPath())

    /**
     * 鏈尾跟著 Burp 偏好設定走，所以純記憶體模式重載後 seq 仍會接續。
     *
     * **依目標 index 分開存。** Burp 的偏好設定是使用者層級、跨專案共用的，但 index 是
     * 每個案子一個。若共用同一個鏈尾，換個 Project ID 之後新 index 的第一筆會宣稱
     * seq 1001、prev_hash 指向一筆只存在於另一個 index 的紀錄 —— 驗證時看起來就是
     * 「開頭缺了一千筆」，而那正是這條鏈要用來偵測的徵狀。
     *
     * 刻意只提供「指定 index」的版本。跟著 [indexName] 走的鏈尾在使用者儲存新的
     * Project ID 之後會悄悄指向另一個專案，而 writer 仍在寫原本那條鏈。
     */
    fun chainTipFor(index: String): ChainTipStore = object : ChainTipStore {
        override fun loadChainTip(): Pair<Long, String> =
            (get("chain.$index.seq", "0").toLongOrNull() ?: 0L) to get("chain.$index.hash", Hashing.GENESIS)

        override fun saveChainTip(seq: Long, hash: String) {
            set("chain.$index.seq", seq.toString())
            set("chain.$index.hash", hash)
        }
    }

    fun indexName(): String = indexNameFor(indexPrefix, projectId)

    fun save() {
        // 先正規化記憶體裡的值再寫出。只 trim 寫出去的那份的話，indexName() 與 tester_id
        // 會沿用帶空白的值直到重載：狀態列誤報「重載後套用另一個 index」，同一案件的
        // 紀錄也會出現兩種 tester_id 寫法。
        esEndpoint = esEndpoint.trim().trimEnd('/')
        esApiKey = esApiKey.trim()
        indexPrefix = indexPrefix.trim()
        testerId = testerId.trim()
        projectId = projectId.trim()
        set("es.endpoint", esEndpoint)
        set("es.apikey", esApiKey)
        set("es.indexPrefix", indexPrefix)
        set("audit.tester", testerId)
        set("audit.project", projectId)
        set("capture.excludedExt", excludedExtensions.joinToString(","))
        setBool("capture.ws", captureWebSockets)
        setBool("capture.fastMode", fastMode)
        setBool("capture.storeBodies", storeBodies)
        setInt("capture.maxBodyBytes", maxStoredBodyBytes)
        setInt("capture.reqTimeout", requestTimeoutSeconds)
        setInt("upload.intervalSec", uploadIntervalSeconds)
        setInt("upload.batchSize", uploadBatchSize)
        setBool("upload.enabled", uploadEnabled)
        set("ui.lang", uiLang)
        setBool("storage.persistLocal", persistLocally)
        set("storage.dbPath", dbPath)
    }

    private fun get(k: String, default: String): String = prefs.getString(k) ?: default
    private fun getInt(k: String, default: Int): Int = prefs.getInteger(k) ?: default
    private fun getBool(k: String, default: Boolean): Boolean = prefs.getBoolean(k) ?: default
    private fun set(k: String, v: String) = prefs.setString(k, v)
    private fun setInt(k: String, v: Int) = prefs.setInteger(k, v)
    private fun setBool(k: String, v: Boolean) = prefs.setBoolean(k, v)

    companion object {
        const val DEFAULT_INDEX_PREFIX = "burp-log"

        /**
         * 設定面板的預覽與實際寫入共用這一個函式。兩邊各自組字串的話，前綴留空時預覽顯示
         * burp-log-<project>，實際卻寫進 <project> —— 那不符合 index template 與 API key 的
         * burp-log-* 範圍，上傳會一直 403。
         */
        fun indexNameFor(prefix: String, project: String): String =
            sanitizeIndex("${prefix.trim().ifBlank { DEFAULT_INDEX_PREFIX }}-${project.trim().ifBlank { "default" }}")

        fun sanitizeIndex(raw: String): String =
            raw.lowercase()
                .replace(Regex("[^a-z0-9._-]"), "-")
                .trimStart('-', '_', '+', '.')
                .ifBlank { "burp-log-default" }

        private fun defaultDbPath(): String {
            val home = System.getProperty("user.home") ?: "."
            val dir = java.io.File(home, ".burp-es-logger")
            dir.mkdirs()
            return java.io.File(dir, "records.db").absolutePath
        }
    }
}
