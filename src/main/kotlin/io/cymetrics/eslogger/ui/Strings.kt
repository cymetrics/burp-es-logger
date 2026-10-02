package io.cymetrics.eslogger.ui

/** 介面語言。 */
enum class Lang(val code: String, val label: String) {
    ZH("zh", "中文"),
    EN("en", "English");

    companion object {
        fun from(code: String): Lang = entries.firstOrNull { it.code == code } ?: ZH
    }
}

/**
 * 介面文案。
 *
 * 刻意不用 ResourceBundle：字串不多，放在同一支檔案裡兩種語言並排，
 * 漏翻或語意漂移一眼就看得到，也不必處理 jar 內的編碼問題。
 * 帶 `%s` 的欄位是樣板，透過對應的 fun 取用。
 */
class Strings(
    val subtitle: String,
    val langLabel: String,

    val sectionConnection: String,
    val sectionIdentity: String,
    val sectionCapture: String,
    val sectionUpload: String,

    val endpoint: String,
    val endpointHint: String,
    val apiKey: String,
    val apiKeyHint: String,
    val showKey: String,
    val indexPrefix: String,
    val testerId: String,
    val projectId: String,
    val projectHint: String,

    val excludedExt: String,
    val excludedHint: String,
    val maxBody: String,
    val reqTimeout: String,
    val reqTimeoutHint: String,
    val storeBodies: String,
    val captureWs: String,
    val fastMode: String,
    val fastModeHint: String,

    val uploadInterval: String,
    val uploadIntervalHint: String,
    val batchSize: String,
    val batchSizeHint: String,
    val autoUpload: String,
    val persistLocal: String,
    val persistLocalHint: String,

    val unitBytes: String,
    val unitSeconds: String,
    val unitRecords: String,

    val tileUploaded: String,
    val tilePending: String,
    val tileQueue: String,
    val tileSpoolMemory: String,
    val tileSpoolDisk: String,
    val tileLastUpload: String,

    val btnSave: String,
    val btnTest: String,
    val btnFlush: String,
    val btnRefresh: String,

    val statusNotConfigured: String,
    val statusUnsaved: String,
    val statusTesting: String,
    val statusUploading: String,
    val statusNothingToUpload: String,
    val statusUnauthorized: String,
    val statusForbidden: String,
    val numberRequired: String,

    private val tplLoaded: String,
    private val tplSaved: String,
    private val tplSaveFailed: String,
    private val tplSentBatch: String,
    private val tplUploadFailed: String,
    private val tplUploadError: String,
    private val tplCredentialsValid: String,
    private val tplConnectionOk: String,
    private val tplUnreachable: String,
    private val tplHttpOther: String,
    private val tplTargetIndex: String,
    private val tplBodyHint: String,
    private val tplDropped: String
) {
    fun loaded(index: String) = tplLoaded.format(index)
    fun saved(index: String) = tplSaved.format(index)
    fun saveFailed(msg: String?) = tplSaveFailed.format(msg ?: "?")
    fun sentBatch(n: Int) = tplSentBatch.format(n)
    fun uploadFailed(msg: String?) = tplUploadFailed.format(msg ?: "?")
    fun uploadError(msg: String) = tplUploadError.format(msg)
    fun credentialsValid(detail: String) = tplCredentialsValid.format(detail)
    fun connectionOk(detail: String) = tplConnectionOk.format(detail)
    fun unreachable(url: String) = tplUnreachable.format(url)
    fun httpOther(code: Int, detail: String) = tplHttpOther.format(code, detail)
    fun targetIndex(name: String) = tplTargetIndex.format(name)
    fun bodyHint(mb: Double) = tplBodyHint.format(mb)
    fun dropped(n: Long) = tplDropped.format(n)

    companion object {
        fun of(lang: Lang): Strings = if (lang == Lang.EN) EN else ZH

        private val ZH = Strings(
            subtitle = "授權滲透測試流量稽核　·　hash chain 完整性　·　批次上傳 Elasticsearch",
            langLabel = "語言",
            sectionConnection = "連線",
            sectionIdentity = "稽核識別",
            sectionCapture = "擷取規則",
            sectionUpload = "上傳",
            endpoint = "Elasticsearch Endpoint",
            endpointHint = "Serverless 端點全名，結尾不要斜線",
            apiKey = "API Key (encoded)",
            apiKeyHint = "只需要 burp-log-* 的 create_doc 權限；別填管理用的 key",
            showKey = "顯示",
            indexPrefix = "Index 前綴",
            testerId = "Tester ID",
            projectId = "Project ID",
            projectHint = "會併進 index 名稱，建議一個案子一個值",
            excludedExt = "排除 body 的副檔名",
            excludedHint = "同時比對副檔名與 Content-Type；排除的 body 仍記 body_len",
            maxBody = "單筆 body 上限",
            reqTimeout = "無回應逾時",
            reqTimeoutHint = "逾時仍以 request_only 落檔，不會漏記",
            storeBodies = "儲存 body（關閉則所有 body 只留 hash）",
            captureWs = "記錄 WebSocket 訊息",
            fastMode = "極速模式：被排除的靜態資源連雜湊都不算",
            fastModeHint = "省掉圖片 / 字型 / CSS 的 SHA-256（這類 body 本來就不保存）。代價是那些訊息事後無法舉證，文件會標記 hashes_skipped。",
            uploadInterval = "上傳間隔",
            uploadIntervalHint = "只是閒置時的輪詢週期；有積壓會連續送，不受這個值限制",
            batchSize = "每批筆數",
            batchSizeHint = "_bulk 用 create + 自訂 _id，重送得到 409 視為已存在，不會重複",
            autoUpload = "啟用自動上傳",
            persistLocal = "待上傳佇列落地到 SQLite",
            persistLocalHint = "關閉＝純記憶體，完全不寫磁碟；Burp 關閉或積壓超過 32 MB 時未送出的會遺失（seq 跳號可辨識）。切換後需重載 extension。",
            unitBytes = "bytes",
            unitSeconds = "秒",
            unitRecords = "筆",
            tileUploaded = "已上傳 (本 session)",
            tilePending = "待上傳",
            tileQueue = "記憶體 queue",
            tileSpoolMemory = "記憶體暫存",
            tileSpoolDisk = "本地暫存",
            tileLastUpload = "上次上傳",
            btnSave = "儲存設定",
            btnTest = "測試連線",
            btnFlush = "立即上傳",
            btnRefresh = "重新整理",
            statusNotConfigured = "尚未設定 Endpoint / API Key，目前不會上傳",
            statusUnsaved = "有未儲存的變更",
            statusTesting = "測試中…",
            statusUploading = "上傳中…",
            statusNothingToUpload = "沒有待上傳資料",
            statusUnauthorized = "HTTP 401 — API key 無效或已撤銷，請重新產生。",
            statusForbidden = "HTTP 403 — 憑證有效，但此 key 無 cluster 讀取權限。這是 append-only key 的預期行為，不影響上傳。",
            numberRequired = "請填入數字",
            tplLoaded = "設定已載入，目標 index：%s",
            tplSaved = "已儲存，目標 index：%s",
            tplSaveFailed = "儲存失敗：%s",
            tplSentBatch = "已送出一批，成功 %d 筆",
            tplUploadFailed = "上傳失敗：%s",
            tplUploadError = "上傳錯誤：%s",
            tplCredentialsValid = "HTTP 200 — 憑證有效：%s",
            tplConnectionOk = "HTTP 200 — 連線正常。%s",
            tplUnreachable = "連線失敗：無法連到 %s",
            tplHttpOther = "HTTP %d — %s",
            tplTargetIndex = "目標 index：%s",
            tplBodyHint = "≈ %.1f MB；超過的 body 只留截斷片段 + 完整長度 + 完整 hash",
            tplDropped = "　(丟棄 %,d)"
        )

        private val EN = Strings(
            subtitle = "Authorized pentest traffic audit　·　hash-chained integrity　·　batched upload to Elasticsearch",
            langLabel = "Language",
            sectionConnection = "Connection",
            sectionIdentity = "Audit identity",
            sectionCapture = "Capture rules",
            sectionUpload = "Upload",
            endpoint = "Elasticsearch endpoint",
            endpointHint = "Full serverless endpoint, no trailing slash",
            apiKey = "API key (encoded)",
            apiKeyHint = "Needs only create_doc on burp-log-*; never paste an admin key here",
            showKey = "Show",
            indexPrefix = "Index prefix",
            testerId = "Tester ID",
            projectId = "Project ID",
            projectHint = "Becomes part of the index name — use one value per engagement",
            excludedExt = "Skip bodies for extensions",
            excludedHint = "Matched against both the extension and Content-Type; skipped bodies still record body_len",
            maxBody = "Max body size",
            reqTimeout = "Response timeout",
            reqTimeoutHint = "On timeout the request is still written as request_only — nothing is dropped",
            storeBodies = "Store bodies (off = keep hashes only)",
            captureWs = "Record WebSocket messages",
            fastMode = "Fast mode: skip hashing for excluded static assets",
            fastModeHint = "Skips SHA-256 for images / fonts / CSS, whose bodies are not stored anyway. The trade-off: those messages can no longer be proven afterwards, and are marked hashes_skipped.",
            uploadInterval = "Upload interval",
            uploadIntervalHint = "Idle polling period only — a backlog is sent back-to-back, not capped by this value",
            batchSize = "Batch size",
            batchSizeHint = "_bulk uses create with a client-side _id, so a 409 on resend counts as already stored — never duplicated",
            autoUpload = "Upload automatically",
            persistLocal = "Spool pending records to SQLite",
            persistLocalHint = "Off = memory only, nothing is written to disk. Anything not yet uploaded is lost when Burp exits or the backlog passes 32 MB (the seq gap makes this visible). Reload the extension after changing this.",
            unitBytes = "bytes",
            unitSeconds = "sec",
            unitRecords = "records",
            tileUploaded = "Uploaded (session)",
            tilePending = "Pending",
            tileQueue = "In-memory queue",
            tileSpoolMemory = "Memory spool",
            tileSpoolDisk = "Local spool",
            tileLastUpload = "Last upload",
            btnSave = "Save settings",
            btnTest = "Test connection",
            btnFlush = "Upload now",
            btnRefresh = "Refresh",
            statusNotConfigured = "Endpoint / API key not set — nothing will be uploaded yet",
            statusUnsaved = "Unsaved changes",
            statusTesting = "Testing…",
            statusUploading = "Uploading…",
            statusNothingToUpload = "Nothing pending",
            statusUnauthorized = "HTTP 401 — API key is invalid or revoked; generate a new one.",
            statusForbidden = "HTTP 403 — credentials are valid but this key cannot read the cluster. Expected for an append-only key; uploads are unaffected.",
            numberRequired = "Enter a number",
            tplLoaded = "Settings loaded — target index: %s",
            tplSaved = "Saved — target index: %s",
            tplSaveFailed = "Save failed: %s",
            tplSentBatch = "Batch sent, %d records accepted",
            tplUploadFailed = "Upload failed: %s",
            tplUploadError = "Upload error: %s",
            tplCredentialsValid = "HTTP 200 — credentials valid: %s",
            tplConnectionOk = "HTTP 200 — connected. %s",
            tplUnreachable = "Connection failed: cannot reach %s",
            tplHttpOther = "HTTP %d — %s",
            tplTargetIndex = "Target index: %s",
            tplBodyHint = "≈ %.1f MB; larger bodies keep a truncated slice plus the full length and hash",
            tplDropped = "　(dropped %,d)"
        )
    }
}
