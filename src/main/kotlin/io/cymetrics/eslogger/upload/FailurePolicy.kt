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
        /** 伺服器端或設定的問題，資料沒錯 —— 退避後重送。 */
        RETRY_LATER,

        /** 整批被拒，但不知道是哪一筆的錯 —— 對半切，逼近出問題的那一筆。 */
        SPLIT_BATCH,

        /** 請求太大 —— 縮小單次上傳的位元組上限再試，不是資料的錯。 */
        SHRINK_LIMIT,

        /** 只剩一筆、而且確知是這筆資料本身的問題 —— 放棄它，讓後面的通過。 */
        DROP_BATCH
    }

    /**
     * [status] 是整個 `_bulk` 請求的 HTTP 狀態碼。
     *
     * 預設是「重試」，只有**確知是這批資料本身有問題**的狀態碼才可能走到丟棄。
     * 反過來做（4xx 一律視為資料有問題）會造成災難：404 其實是 endpoint 打錯字，
     * 但切批邏輯會一路切到單筆再逐筆丟棄 —— 一個字母的錯字就刪光整場測試的證據。
     * 401 / 403 是金鑰問題、429 是流量限制、404 / 405 是設定問題，資料全都沒錯。
     */
    fun classifyHttp(status: Int, batchSize: Int): BatchOutcome = when {
        // 請求過大是中介設備或叢集的限制，縮小再送即可，不該刪資料
        status == 413 -> if (batchSize > 1) BatchOutcome.SPLIT_BATCH else BatchOutcome.SHRINK_LIMIT
        status in DATA_FAULT_STATUSES ->
            if (batchSize > 1) BatchOutcome.SPLIT_BATCH else BatchOutcome.DROP_BATCH
        else -> BatchOutcome.RETRY_LATER
    }

    /** 唯一會導向丟棄的狀態碼：ES 認為這份文件本身不合法。 */
    private val DATA_FAULT_STATUSES = setOf(400, 422)

    fun shouldDropDocument(attempts: Int): Boolean = attempts >= MAX_DOC_ATTEMPTS
}
