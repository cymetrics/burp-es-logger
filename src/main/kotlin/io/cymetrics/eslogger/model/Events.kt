package io.cymetrics.eslogger.model

import java.time.Instant

/**
 * 從 Burp thread 抽出來的純資料（不持有 Montoya 物件），丟進 queue 給 writer 處理。
 * 這樣擷取回呼就不會阻塞 Burp 的請求路徑。
 *
 * 刻意保留「整包原始訊息 + body 起始位移」而不是切成 head / body 兩個陣列：
 * 切開要多複製一整份訊息，算 raw hash 時又得再串回去。保留 raw 之後，
 * header 解碼、body hash、body 擷取都能用 offset/length 就地處理。
 */
sealed class CaptureEvent {
    /** 這個事件在 queue 裡佔用的位元組，用來做記憶體上限控制。 */
    abstract fun sizeBytes(): Int
}

data class ReqData(
    val ts: Instant,
    val tool: String,
    val method: String,
    val url: String,
    val host: String,
    val port: Int,
    val secure: Boolean,
    /** 完整請求（request line + headers + body）。 */
    val raw: ByteArray,
    /** body 在 raw 裡的起始位移。 */
    val bodyOffset: Int
) {
    val bodyLength: Int get() = raw.size - bodyOffset
}

data class RespData(
    val ts: Instant,
    val statusCode: Int,
    val raw: ByteArray,
    val bodyOffset: Int
) {
    val bodyLength: Int get() = raw.size - bodyOffset
}

data class RequestCaptured(val messageId: Int, val data: ReqData) : CaptureEvent() {
    override fun sizeBytes(): Int = data.raw.size + OVERHEAD
}

data class ResponseCaptured(val messageId: Int, val data: RespData) : CaptureEvent() {
    override fun sizeBytes(): Int = data.raw.size + OVERHEAD
}

data class WsCaptured(
    val ts: Instant,
    val tool: String,
    val url: String,
    val direction: String,   // CLIENT_TO_SERVER / SERVER_TO_CLIENT
    val isText: Boolean,
    val payload: ByteArray
) : CaptureEvent() {
    override fun sizeBytes(): Int = payload.size + OVERHEAD
}

/** 物件本身（字串欄位、header）的粗估固定成本。 */
private const val OVERHEAD = 512
