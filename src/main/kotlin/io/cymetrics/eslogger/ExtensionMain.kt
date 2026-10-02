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

class ExtensionMain : BurpExtension {

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
        api.http().registerHttpHandler(HttpCaptureHandler(writer))

        // WebSocket（可在設定關閉；關閉時仍註冊但建議重載 extension 以完全停止）
        if (config.captureWebSockets) {
            try {
                WebSocketCaptureHandler.register(api, writer)
            } catch (t: Throwable) {
                log.logToError("[es-logger] WebSocket 註冊失敗（可能是 Montoya 版本差異）：${t.message}")
            }
        }

        // 設定 / 狀態分頁
        val panel = SettingsPanel(config, store, uploader, writer)
        // 讓面板跟著 Burp 的淺色 / 深色佈景走，否則自訂元件在深色模式下會變成黑底黑字。
        api.userInterface().applyThemeToComponent(panel)
        api.userInterface().registerSuiteTab("ES Logger", panel)

        // 關閉 / 重載時 flush
        api.extension().registerUnloadingHandler {
            log.logToOutput("[es-logger] unloading, flushing…")
            writer.stop()
            uploader.stop()
            store.close()
        }

        val mode = if (config.persistLocally) "SQLite ${config.dbPath}" else "純記憶體（不落地）"
        log.logToOutput("[es-logger] 已載入。待上傳佇列：$mode　|　Index：${config.indexName()}")
        log.logToOutput("[es-logger] 記得到 ES Logger 分頁填入 Endpoint / API Key / Tester / Project 後按儲存。")
    }
}
