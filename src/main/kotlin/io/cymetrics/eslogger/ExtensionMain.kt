package io.cymetrics.eslogger

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging
import io.cymetrics.eslogger.capture.HttpCaptureHandler
import io.cymetrics.eslogger.capture.WebSocketCaptureHandler
import io.cymetrics.eslogger.config.Config
import io.cymetrics.eslogger.core.RecordWriter
import io.cymetrics.eslogger.storage.MemorySpool
import io.cymetrics.eslogger.storage.RecordSpool
import io.cymetrics.eslogger.storage.SqliteStore
import io.cymetrics.eslogger.ui.SettingsPanel
import io.cymetrics.eslogger.upload.ElasticUploader
import javax.swing.SwingUtilities

class ExtensionMain : BurpExtension {

    internal companion object {
        /** 卸載時願意花多久把待上傳資料送完；超過就放手，不要卡住 Burp 關閉。 */
        private const val UNLOAD_UPLOAD_BUDGET_MS = 15_000L

        /**
         * SQLite 開不起來（檔案損毀、權限、舊版資料衝突）時改用記憶體 spool 繼續記錄。
         *
         * 讓 initialize 直接失敗的話，擴充不會載入、什麼都不記，而且每次重開都一樣 ——
         * 那段流量在 index 裡連缺號都不會留下。DB 檔維持原狀，修好後重載即恢復落地。
         */
        fun openSpool(config: Config, targetIndex: String, log: Logging): RecordSpool {
            if (!config.persistLocally) return MemorySpool()
            return try {
                SqliteStore(config.dbPath, targetIndex, config.chainTipFor(targetIndex).loadChainTip(), config.projectId)
            } catch (t: Throwable) {
                log.logToError(
                    "[es-logger] cannot open the SQLite spool at ${config.dbPath}: ${t.message} — recording with " +
                        "the in-memory spool for this session. The file is left untouched; fix or move it, then reload."
                )
                log.raiseCriticalEvent("ES Logger: SQLite spool unavailable, using the in-memory spool until reload")
                MemorySpool()
            }
        }
    }

    override fun initialize(api: MontoyaApi) {
        api.extension().setName("ES Logger")
        val log = api.logging()

        val config = Config(api.persistence().preferences())
        // 目標 index 只在這裡決定一次，spool、writer、uploader 共用同一個值。之後在面板
        // 儲存新的 Project ID 只影響下一次載入，不會讓三者各自指向不同的 index。
        val targetIndex = config.indexName()
        val store = openSpool(config, targetIndex, log)
        if (store is SqliteStore && store.legacyPendingCount > 0) {
            log.logToError(
                "[es-logger] ${store.legacyPendingCount} legacy record(s) are preserved but not uploaded: " +
                    "their destination cannot be confirmed. Reload with the original index/project and " +
                    "its saved chain preferences to recover them; keep the SQLite file for recovery."
            )
            log.raiseCriticalEvent("ES Logger: legacy SQLite records need their original project before upload")
        }
        val writer = RecordWriter(config, store, log, targetIndex)
        val uploader = ElasticUploader(config, store, log, targetIndex = targetIndex)

        writer.start()
        uploader.start()

        // 所有工具的 HTTP 流量
        val httpRegistration = api.http().registerHttpHandler(HttpCaptureHandler(writer))

        // WebSocket（可在設定關閉；關閉時仍註冊但建議重載 extension 以完全停止）
        if (config.captureWebSockets) {
            try {
                WebSocketCaptureHandler.register(api, writer)
            } catch (t: Throwable) {
                log.logToError("[es-logger] WebSocket capture unavailable on this Montoya version: ${t.message}")
            }
        }

        // 設定 / 狀態分頁
        // Swing 元件必須在 EDT 上建構。這裡跑在 Burp 的擴充載入執行緒上，直接 new 出來
        // 會在 add/revalidate/repaint 時與 EDT 競爭，症狀是分頁偶爾空白或只畫一半。
        lateinit var panel: SettingsPanel
        SwingUtilities.invokeAndWait {
            panel = SettingsPanel(config, store, uploader, writer)
            // 讓面板跟著 Burp 的淺色 / 深色佈景走，否則自訂元件在深色模式下會變成黑底黑字。
            api.userInterface().applyThemeToComponent(panel)
        }
        api.userInterface().registerSuiteTab("ES Logger", panel)
        // 註冊成功之後才啟動，這樣中途失敗不會留下一個沒人停得掉的計時器
        SwingUtilities.invokeAndWait { panel.start() }

        // 關閉 / 重載時 flush
        api.extension().registerUnloadingHandler {
            log.logToOutput("[es-logger] unloading — draining the queue and flushing pending records")
            // 先拔掉 handler：否則卸載期間仍有流量進來，那些訊息拿不到 seq，
            // 遺失不會以缺號呈現。
            try { httpRegistration.deregister() } catch (t: Throwable) {
                log.logToError("[es-logger] could not deregister the HTTP handler: ${t.message}")
            }
            writer.stop()
            // 等 uploader 執行緒收工，drain 才能獨佔地跑 —— 否則兩者會同時抓同一批，
            // 計數器與批次二分邏輯都會互相干擾。
            uploader.stop()
            val stranded = uploader.drainBeforeExit(UNLOAD_UPLOAD_BUDGET_MS)
            if (stranded > 0) {
                log.logToError(
                    "[es-logger] $stranded record(s) were still pending at unload" +
                        if (store is SqliteStore) " and remain in the local spool"
                        else " and were lost — the gap is visible as a jump in seq"
                )
            }
            store.close()
            // 停掉面板的 5 秒計時器：不停的話它會繼續觸發，並且讓面板連同 API key 無法回收
            SwingUtilities.invokeLater { panel.dispose() }
        }

        val spool = if (store is SqliteStore) "SQLite at ${config.dbPath}" else "in-memory (nothing written to disk)"
        val fastMode = if (config.fastMode) "on (excluded static assets skip the body hash)" else "off (everything is hashed)"
        log.logToOutput("[es-logger] loaded — spool: $spool | index: $targetIndex | fast mode: $fastMode")
        // 只在真的還沒設定時才提醒，不要每次載入都嘮叨
        if (config.esEndpoint.isBlank() || config.esApiKey.isBlank()) {
            log.logToOutput(
                "[es-logger] not uploading yet — set the endpoint, API key, tester and project " +
                    "in the ES Logger tab, then save"
            )
        }
    }
}
