package io.cymetrics.eslogger.capture

import burp.api.montoya.MontoyaApi
import burp.api.montoya.websocket.BinaryMessage
import burp.api.montoya.websocket.BinaryMessageAction
import burp.api.montoya.websocket.MessageHandler
import burp.api.montoya.websocket.TextMessage
import burp.api.montoya.websocket.TextMessageAction
import io.cymetrics.eslogger.core.RecordWriter
import io.cymetrics.eslogger.model.WsCaptured
import java.time.Instant

/**
 * WebSocket 訊息記錄。HttpHandler 不會經過 WebSocket，所以要另外註冊。
 *
 * 註：不同 Montoya 版本的 websocket 介面名稱可能略有差異
 * （MessageHandler / TextMessage / BinaryMessage / Direction）。
 * 若編譯不過，依你使用的 montoya-api 版本調整 import 即可。
 */
object WebSocketCaptureHandler {

    fun register(api: MontoyaApi, writer: RecordWriter) {
        api.websockets().registerWebSocketCreatedHandler { created ->
            val url = try { created.upgradeRequest().url() } catch (_: Throwable) { "" }
            val tool = "WebSocket"

            created.webSocket().registerMessageHandler(object : MessageHandler {
                override fun handleTextMessage(message: TextMessage): TextMessageAction {
                    try {
                        writer.submit(
                            WsCaptured(
                                ts = Instant.now(),
                                tool = tool,
                                url = url,
                                direction = message.direction().name,
                                isText = true,
                                payload = message.payload().toByteArray(Charsets.UTF_8)
                            )
                        )
                    } catch (_: Throwable) {}
                    return TextMessageAction.continueWith(message)
                }

                override fun handleBinaryMessage(message: BinaryMessage): BinaryMessageAction {
                    try {
                        writer.submit(
                            WsCaptured(
                                ts = Instant.now(),
                                tool = tool,
                                url = url,
                                direction = message.direction().name,
                                isText = false,
                                payload = message.payload().bytes
                            )
                        )
                    } catch (_: Throwable) {}
                    return BinaryMessageAction.continueWith(message)
                }
            })
        }
    }
}
