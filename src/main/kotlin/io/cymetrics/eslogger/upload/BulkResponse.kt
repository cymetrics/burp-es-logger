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

    data class Rejected(val row: Pending, val status: Int, val reason: String)

    data class Result(
        val accepted: List<Pending>,
        /** 被拒絕的那幾筆，附上 ES 給的原因。 */
        val rejected: List<Rejected>
    )

    /**
     * [body] 是 HTTP 2xx 的 `_bulk` 回應。`items` 的順序與送出順序一致，以索引對位。
     *
     * 回應必須能逐筆對應。無法確認時保留整批並重試；create + 固定 _id 讓重送安全。
     */
    fun parse(body: String, batch: List<Pending>): Result {
        val items = try {
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("items")
        } catch (e: Exception) {
            throw IllegalStateException("Invalid bulk response; records retained for retry", e)
        } ?: throw IllegalStateException("Bulk response has no items; records retained for retry")
        check(items.size() == batch.size) { "Bulk response item count does not match the submitted batch" }

        val accepted = ArrayList<Pending>(batch.size)
        val rejected = ArrayList<Rejected>()

        for ((i, item) in items.withIndex()) {
            val row = batch[i]
            val create = try {
                item.asJsonObject.getAsJsonObject("create")
            } catch (e: Exception) {
                throw IllegalStateException("Invalid bulk create result at item $i", e)
            } ?: throw IllegalStateException("Bulk response is missing create result at item $i")
            val statusValue = create.get("status")
            check(statusValue != null && statusValue.isJsonPrimitive &&
                statusValue.asJsonPrimitive.isNumber && statusValue.asString.matches(Regex("[1-5][0-9]{2}"))) {
                "Bulk response is missing a valid status at item $i"
            }
            val status = statusValue.asInt
            create.get("_id")?.let { check(it.asString == row.docId) { "Bulk response document ID mismatch at item $i" } }
            // 409 = 這個 _id 已經存在。自訂 _id 讓重送具冪等性，所以這也是成功。
            if (status == 200 || status == 201 || status == 409) {
                accepted.add(row)
            } else {
                rejected.add(Rejected(row, status, create.get("error")?.toString()?.take(200) ?: "status=$status"))
            }
        }
        return Result(accepted, rejected)
    }
}
