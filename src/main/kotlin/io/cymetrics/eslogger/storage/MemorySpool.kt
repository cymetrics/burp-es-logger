package io.cymetrics.eslogger.storage

import io.cymetrics.eslogger.integrity.Hashing

/**
 * 純記憶體 outbox：完全不寫磁碟。
 *
 * 取捨（使用者明確選擇）：
 *  - Burp 關閉 / extension 重載 / 積壓超過 [maxBytes] → 還沒送出的紀錄直接消失。
 *  - 丟棄時從最舊的開始丟，並累計 [droppedCount]。因為 seq 連續遞增，
 *    ES 端看到的就是跳號 —— 缺漏是「看得見」的，不會偽裝成完整紀錄。
 *  - 鏈尾（seq 與上一筆 hash）存在 Burp 偏好設定裡，所以重載 extension 後 seq 會接續，
 *    不會在同一個 index 裡產生重複的 seq。
 *
 * 這就是「不是 100% 紀錄」的具體含意：它保證寫進 ES 的每一筆都可驗證，
 * 但不保證每一筆流量都進得了 ES。
 */
class MemorySpool(
    private val chainTip: ChainTipStore,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) : RecordSpool {

    private val lock = Any()
    private val queue = ArrayDeque<Pending>()
    private var bytes = 0L

    @Volatile override var lastSeq: Long = 0
        private set
    @Volatile override var lastHash: String = Hashing.GENESIS
        private set

    init {
        val (seq, hash) = chainTip.loadChainTip()
        lastSeq = seq
        lastHash = hash
    }
    @Volatile override var pendingCount: Long = 0
        private set
    @Volatile override var droppedCount: Long = 0
        private set

    override val kind: SpoolKind get() = SpoolKind.MEMORY

    override fun usageBytes(): Long = synchronized(lock) { bytes }

    override fun insertAll(records: List<NewRecord>) = synchronized(lock) {
        for (r in records) {
            queue.addLast(Pending(r.seq, r.docId, r.docJson))
            bytes += r.docJson.length.toLong()
        }
        // 超過上限就丟最舊的：寧可缺一段舊的，也不要讓 Burp 的記憶體無限長大。
        while (bytes > maxBytes && queue.isNotEmpty()) {
            bytes -= queue.removeFirst().docJson.length.toLong()
            droppedCount++
        }
        val last = records.last()
        lastSeq = last.seq
        lastHash = last.recordHash
        chainTip.saveChainTip(last.seq, last.recordHash)
        pendingCount = queue.size.toLong()
    }

    override fun pendingBatch(limit: Int): List<Pending> = synchronized(lock) {
        if (queue.size <= limit) queue.toList() else queue.take(limit)
    }

    override fun purge(seqs: Collection<Long>): Int = synchronized(lock) {
        if (seqs.isEmpty()) return@synchronized 0
        val target = seqs.toHashSet()
        var removed = 0
        val it = queue.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.seq in target) {
                it.remove()
                bytes -= p.docJson.length.toLong()
                removed++
            }
        }
        pendingCount = queue.size.toLong()
        removed
    }

    override fun close() = synchronized(lock) {
        queue.clear()
        bytes = 0
        pendingCount = 0
    }

    private companion object {
        /** 記憶體 outbox 上限；和 writer 佇列的 64 MB 是分開的兩段預算。 */
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
    }
}
