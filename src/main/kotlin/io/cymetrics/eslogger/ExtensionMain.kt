package io.cymetrics.eslogger

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
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

    private companion object {
        /** 卸載時願意花多久把待上傳資料送完；超過就放手，不要卡住 Burp 關閉。 */
        const val UNLOAD_UPLOAD_BUDGET_MS = 15_000L
    }

    override fun initialize(api: MontoyaApi) {
        api.extension().setName("ES Logger")
        val log = api.logging()

        val config = Config(api.persistence().preferences())
        val store: RecordSpool =
            if (config.persistLocally) SqliteStore(config.dbPath) else MemorySpool()
        val writer = RecordWriter(config, store, log)
        val uploader = ElasticUploader(config, store, log)

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
                        if (config.persistLocally) " and remain in the local spool"
                        else " and were lost — the gap is visible as a jump in seq"
                )
            }
            store.close()
            // 停掉面板的 5 秒計時器：不停的話它會繼續觸發，並且讓面板連同 API key 無法回收
            SwingUtilities.invokeLater { panel.dispose() }
        }

        val spool = if (config.persistLocally) "SQLite at ${config.dbPath}" else "in-memory (nothing written to disk)"
        val fastMode = if (config.fastMode) "on (excluded static assets are not hashed)" else "off (everything is hashed)"
        log.logToOutput("[es-logger] loaded — spool: $spool | index: ${config.indexName()} | fast mode: $fastMode")
        // 只在真的還沒設定時才提醒，不要每次載入都嘮叨
        if (config.esEndpoint.isBlank() || config.esApiKey.isBlank()) {
            log.logToOutput(
                "[es-logger] not uploading yet — set the endpoint, API key, tester and project " +
                    "in the ES Logger tab, then save"
            )
        }
    }
}
