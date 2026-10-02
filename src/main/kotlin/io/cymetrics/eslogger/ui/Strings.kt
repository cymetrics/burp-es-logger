package io.cymetrics.eslogger.ui

/** 介面語言。 */
enum class Lang(val code: String, val label: String) {
    ZH("zh", "中文"),
    EN("en", "English");

    /** JComboBox 直接呼叫 toString()，不覆寫就會顯示 ZH / EN。 */
    override fun toString(): String = label

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
            subtitle = "授權滲透測試流量稽核　·　hash chain 完整性驗證　·　批次上傳 Elasticsearch",
            langLabel = "語言",
            sectionConnection = "連線",
            sectionIdentity = "稽核識別",
            sectionCapture = "擷取規則",
            sectionUpload = "上傳",
            endpoint = "Endpoint",
            endpointHint = "完整的 Serverless 端點位址，結尾不含斜線",
            apiKey = "API key",
            apiKeyHint = "僅需 burp-log-* 的 create_doc 權限。請勿使用具管理權限的金鑰。",
            showKey = "顯示",
            indexPrefix = "Index 前綴",
            testerId = "Tester ID",
            projectId = "Project ID",
            projectHint = "將併入 index 名稱，建議每個案件使用獨立值。",
            excludedExt = "排除類型",
            excludedHint = "同時比對副檔名與 Content-Type。排除的 body 仍會記錄長度與雜湊。",
            maxBody = "body 上限",
            reqTimeout = "回應逾時",
            reqTimeoutHint = "逾時後仍以 request_only 形式記錄，不會遺漏任何請求。",
            storeBodies = "保存 body（關閉時僅記錄雜湊）",
            captureWs = "記錄 WebSocket 訊息",
            fastMode = "極速模式：排除的靜態資源不計算雜湊",
            fastModeHint = "略過圖片、字型與 CSS 的 SHA-256 計算，這類 body 本就不會保存。代價是這些訊息日後無法舉證，文件中會標記 hashes_skipped。",
            uploadInterval = "上傳間隔",
            uploadIntervalHint = "僅為閒置時的輪詢週期。有待上傳資料時會連續送出，不受此值限制。",
            batchSize = "每批筆數",
            batchSizeHint = "_bulk 以 create 搭配自訂 _id 送出。重送時回應 409 即視為已存在，不會產生重複文件。",
            autoUpload = "啟用自動上傳",
            persistLocal = "待上傳佇列寫入本機 SQLite",
            persistLocalHint = "關閉時僅使用記憶體，不寫入任何檔案。Burp 結束或積壓超過 32 MB 時，尚未送出的紀錄將遺失，並可由 seq 跳號辨識。變更後需重新載入擴充。",
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
            statusNotConfigured = "尚未設定 Endpoint 與 API Key，目前不會上傳",
            statusUnsaved = "有未儲存的變更",
            statusTesting = "測試中…",
            statusUploading = "上傳中…",
            statusNothingToUpload = "沒有待上傳資料",
            statusUnauthorized = "HTTP 401 — API 金鑰無效或已撤銷，請重新產生。",
            statusForbidden = "HTTP 403 — 憑證有效，但此金鑰無 cluster 讀取權限。此為 append-only 金鑰的預期行為，不影響上傳。",
            numberRequired = "請填入數字",
            tplLoaded = "設定已載入，目標 index：%s",
            tplSaved = "已儲存，目標 index：%s",
            tplSaveFailed = "儲存失敗：%s",
            tplSentBatch = "已送出一批，成功寫入 %d 筆",
            tplUploadFailed = "上傳失敗：%s",
            tplUploadError = "上傳錯誤：%s",
            tplCredentialsValid = "HTTP 200 — 憑證有效：%s",
            tplConnectionOk = "HTTP 200 — 連線正常。%s",
            tplUnreachable = "連線失敗：無法連到 %s",
            tplHttpOther = "HTTP %d — %s",
            tplTargetIndex = "目標 index：%s",
            tplBodyHint = "約 %.1f MB。超出部分僅保留截斷片段，並記錄完整長度與雜湊。",
            tplDropped = "　(丟棄 %,d)"
        )

        private val EN = Strings(
            subtitle = "Authorized pentest traffic audit　·　hash-chained integrity　·　batched upload to Elasticsearch",
            langLabel = "Language",
            sectionConnection = "Connection",
            sectionIdentity = "Audit identity",
            sectionCapture = "Capture rules",
            sectionUpload = "Upload",
            endpoint = "Endpoint",
            endpointHint = "Full serverless endpoint address, without a trailing slash.",
            apiKey = "API key",
            apiKeyHint = "Requires only create_doc on burp-log-*. Do not use a key with administrative privileges.",
            showKey = "Show",
            indexPrefix = "Index prefix",
            testerId = "Tester ID",
            projectId = "Project ID",
            projectHint = "Becomes part of the index name. Use a distinct value per engagement.",
            excludedExt = "Excluded types",
            excludedHint = "Matched against both the file extension and the Content-Type. Skipped bodies still record their length and hash.",
            maxBody = "Max body size",
            reqTimeout = "Response timeout",
            reqTimeoutHint = "On timeout the request is still recorded as request_only. No request is dropped.",
            storeBodies = "Store bodies (when off, only hashes are recorded)",
            captureWs = "Record WebSocket messages",
            fastMode = "Fast mode: skip hashing for excluded static assets",
            fastModeHint = "Skips SHA-256 for images, fonts and CSS, whose bodies are not stored in any case. The trade-off is that those messages can no longer be evidenced later; they are marked hashes_skipped.",
            uploadInterval = "Upload interval",
            uploadIntervalHint = "Idle polling period only. A backlog is sent back-to-back and is not limited by this value.",
            batchSize = "Batch size",
            batchSizeHint = "_bulk uses create with a client-generated _id. A 409 on resend means the document is already stored, so nothing is duplicated.",
            autoUpload = "Upload automatically",
            persistLocal = "Spool pending records to local SQLite",
            persistLocalHint = "When off, records are held in memory only and nothing is written to disk. Anything not yet uploaded is lost when Burp exits or the backlog exceeds 32 MB; the resulting gap in seq makes this detectable. Reload the extension after changing this.",
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
            statusNotConfigured = "Endpoint and API key are not set; nothing will be uploaded yet",
            statusUnsaved = "Unsaved changes",
            statusTesting = "Testing…",
            statusUploading = "Uploading…",
            statusNothingToUpload = "Nothing pending",
            statusUnauthorized = "HTTP 401 — the API key is invalid or has been revoked. Generate a new one.",
            statusForbidden = "HTTP 403 — the credentials are valid but this key cannot read the cluster. This is expected for an append-only key and does not affect uploads.",
            numberRequired = "Enter a number",
            tplLoaded = "Settings loaded — target index: %s",
            tplSaved = "Saved — target index: %s",
            tplSaveFailed = "Save failed: %s",
            tplSentBatch = "Batch sent; %d records accepted",
            tplUploadFailed = "Upload failed: %s",
            tplUploadError = "Upload error: %s",
            tplCredentialsValid = "HTTP 200 — credentials valid: %s",
            tplConnectionOk = "HTTP 200 — connected. %s",
            tplUnreachable = "Connection failed: cannot reach %s",
            tplHttpOther = "HTTP %d — %s",
            tplTargetIndex = "Target index: %s",
            tplBodyHint = "Approximately %.1f MB. Larger bodies keep a truncated slice plus the full length and hash.",
            tplDropped = "　(dropped %,d)"
        )
    }
}
