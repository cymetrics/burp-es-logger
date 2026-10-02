package io.cymetrics.eslogger.storage

/** 等待上傳的一筆紀錄（writer 產出）。 */
data class NewRecord(
    val seq: Long,
    val docId: String,
    val recordHash: String,
    val docJson: String
)

/** 要送往 ES 的一筆（uploader 取用）。 */
data class Pending(
    val seq: Long,
    val docId: String,
    val docJson: String
)

/**
 * 待上傳佇列的儲存後端。
 *
 * 兩種實作的差別只有「撐不住的時候怎麼辦」：
 *  - [MemorySpool]：不碰磁碟，超過上限就丟掉最舊的，seq 會跳號。
 *  - [SqliteStore]：落地，積壓多少就留多少，不會因為量大而丟資料。
 *
 * 兩者都是 outbox 語意：確認進了 ES 就 [purge]，不保留副本。
 */
enum class SpoolKind { MEMORY, DISK }

interface RecordSpool {

    /** hash chain 的鏈尾，writer 啟動時從這裡接續。 */
    val lastSeq: Long
    val lastHash: String

    /** 還沒確認上傳的筆數。 */
    val pendingCount: Long

    /** 因為超過上限而被丟棄的累計筆數（落地模式永遠是 0）。 */
    val droppedCount: Long

    /** 後端種類，UI 依此選用文案。 */
    val kind: SpoolKind

    /** 目前佔用的位元組。 */
    fun usageBytes(): Long

    fun insertAll(records: List<NewRecord>)

    fun pendingBatch(limit: Int): List<Pending>

    /** 刪除已確認進 ES 的紀錄，回傳實際刪掉的筆數。 */
    fun purge(seqs: Collection<Long>): Int

    fun close()
}
