package io.cymetrics.eslogger.upload

/**
 * 上傳失敗的分類。
 *
 * 關鍵區別是「ES 暫時不行」與「這筆永遠進不去」。全部當成前者就會無限重送，
 * 一筆 mapping 衝突的文件足以卡住它後面所有紀錄；全部當成後者則會在 ES 短暫中斷時
 * 丟掉完全正常的資料。
 */
object FailurePolicy {

    /** 同一筆被拒絕幾次之後放棄。給 ES 幾次機會，但不是無限次。 */
    const val MAX_DOC_ATTEMPTS = 3

    enum class BatchOutcome {
        /** 伺服器端的問題，資料沒錯 —— 退避後重送。 */
        RETRY_LATER,

        /** 整批被拒，但不知道是哪一筆的錯 —— 對半切，逼近出問題的那一筆。 */
        SPLIT_BATCH,

        /** 只剩一筆還是被拒 —— 放棄這筆，讓後面的通過。 */
        DROP_BATCH
    }

    /**
     * [status] 是整個 `_bulk` 請求的 HTTP 狀態碼。
     *
     * 401 / 403 / 429 刻意歸類為可重試：那是金鑰或流量的問題，不是資料的問題，
     * 丟掉等於拿使用者的設定錯誤去懲罰稽核紀錄。
     */
    fun classifyHttp(status: Int, batchSize: Int): BatchOutcome {
        val dataIsAtFault = status in 400..499 && status != 401 && status != 403 && status != 429
        return when {
            !dataIsAtFault -> BatchOutcome.RETRY_LATER
            batchSize > 1 -> BatchOutcome.SPLIT_BATCH
            else -> BatchOutcome.DROP_BATCH
        }
    }

    fun shouldDropDocument(attempts: Int): Boolean = attempts >= MAX_DOC_ATTEMPTS
}
