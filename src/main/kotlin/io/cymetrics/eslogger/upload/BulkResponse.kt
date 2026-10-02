package io.cymetrics.eslogger.upload

import com.google.gson.JsonParser
import io.cymetrics.eslogger.storage.Pending

/**
 * `_bulk` 回應的判讀，刻意抽成純函式。
 *
 * 這是上傳迴圈裡最容易悄悄出錯的地方：誤判成功會讓本地刪掉實際沒進 ES 的紀錄，
 * 誤判失敗則會讓同一批無限重送。抽出來之後不需要 Burp、不需要 ES 就能測。
 */
object BulkResponse {

    data class Result(
        val accepted: List<Pending>,
        /** 被拒絕的那幾筆，附上 ES 給的原因。 */
        val rejected: List<Pair<Pending, String>>
    )

    /**
     * [body] 是 HTTP 2xx 的 `_bulk` 回應。`items` 的順序與送出順序一致，以索引對位。
     *
     * 判讀不出來（沒有 items、或根本不是 JSON）時保守視為全部成功：走到這裡代表
     * HTTP 已經是 2xx，若當成失敗就會把已經寫進 ES 的資料再送一輪。
     */
    fun parse(body: String, batch: List<Pending>): Result {
        val items = try {
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("items")
        } catch (t: Throwable) {
            null
        } ?: return Result(batch, emptyList())

        val accepted = ArrayList<Pending>(batch.size)
        val rejected = ArrayList<Pair<Pending, String>>()

        for ((i, item) in items.withIndex()) {
            val row = batch.getOrNull(i) ?: continue   // 回應比送出的還多筆就忽略
            val create = try {
                item.asJsonObject.getAsJsonObject("create")
            } catch (t: Throwable) {
                null
            } ?: continue
            val status = create.get("status")?.asInt ?: 0
            // 409 = 這個 _id 已經存在。自訂 _id 讓重送具冪等性，所以這也是成功。
            if (status == 200 || status == 201 || status == 409) {
                accepted.add(row)
            } else {
                rejected.add(row to (create.get("error")?.toString()?.take(200) ?: "status=$status"))
            }
        }
        return Result(accepted, rejected)
    }
}
