package io.cymetrics.eslogger.capture

import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import io.cymetrics.eslogger.core.RecordWriter
import io.cymetrics.eslogger.model.ReqData
import io.cymetrics.eslogger.model.RequestCaptured
import io.cymetrics.eslogger.model.RespData
import io.cymetrics.eslogger.model.ResponseCaptured
import java.time.Instant

/**
 * 掛在 api.http() 上，會收到所有工具（Proxy / Repeater / Intruder / Scanner / 其他 extension）
 * 送出的 HTTP 流量。
 *
 * 這裡跑在 Burp 的請求路徑上，每多一次陣列複製就是每一筆流量都要付的成本，
 * 所以只做「拿到原始位元組 + 記下 body 位移 + 丟 queue」，不做任何切割或雜湊。
 */
class HttpCaptureHandler(private val writer: RecordWriter) : HttpHandler {

    override fun handleHttpRequestToBeSent(req: HttpRequestToBeSent): RequestToBeSentAction {
        try {
            val raw = req.toByteArray().bytes
            val svc = req.httpService()
            writer.submit(
                RequestCaptured(
                    req.messageId(),
                    ReqData(
                        ts = Instant.now(),
                        tool = toolName(req),
                        method = req.method(),
                        url = safeUrl(req),
                        host = svc?.host() ?: "",
                        port = svc?.port() ?: -1,
                        secure = svc?.secure() ?: false,
                        raw = raw,
                        bodyOffset = req.bodyOffset().coerceIn(0, raw.size)
                    )
                )
            )
        } catch (_: Throwable) {
            // 擷取失敗絕不能影響 Burp 正常運作
        }
        return RequestToBeSentAction.continueWith(req)
    }

    override fun handleHttpResponseReceived(resp: HttpResponseReceived): ResponseReceivedAction {
        try {
            val raw = resp.toByteArray().bytes
            writer.submit(
                ResponseCaptured(
                    resp.messageId(),
                    RespData(
                        ts = Instant.now(),
                        statusCode = resp.statusCode().toInt(),
                        raw = raw,
                        bodyOffset = resp.bodyOffset().coerceIn(0, raw.size)
                    )
                )
            )
        } catch (_: Throwable) {
        }
        return ResponseReceivedAction.continueWith(resp)
    }

    private fun toolName(req: HttpRequestToBeSent): String =
        try { req.toolSource().toolType().toolName() } catch (_: Throwable) { "unknown" }

    private fun safeUrl(req: HttpRequestToBeSent): String =
        try { req.url() } catch (_: Throwable) { "" }
}
