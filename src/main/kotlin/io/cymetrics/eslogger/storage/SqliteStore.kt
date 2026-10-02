package io.cymetrics.eslogger.storage

import io.cymetrics.eslogger.integrity.Hashing
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.Instant

/**
 * 本地 **outbox**（不是封存）。
 *
 * 設計取捨：成功進到 ES 的紀錄會立刻從本地刪掉，本機只留還沒送出去的部分，
 * 所以 DB 大小正比於「積壓量」而不是「總流量」；ES 可達時這張表通常是空的。
 * 代價是本地不再有完整副本，**ES 就是唯一的稽核來源**。
 *
 * hash chain 不受影響：鏈只需要「上一筆的 hash」就能接下去。鏈尾存在 [meta] 表，
 * 和紀錄寫入在同一個交易裡 commit，所以 outbox 被清空、甚至整個 session 重來都接得下去。
 *
 * 執行緒：單一連線 + 一把鎖。writer 批次寫、uploader 批次刪，UI 只讀記憶體計數器，
 * 不碰 SQL（原本 UI 每 5 秒一次 COUNT(*) 全表掃描，表一大就會卡住 writer）。
 */
class SqliteStore(private val dbPath: String) : RecordSpool {

    private val conn: Connection
    private val lock = Any()

    private val insertStmt: PreparedStatement
    private val pendingStmt: PreparedStatement
    private val metaStmt: PreparedStatement

    /** 鏈尾：下一筆要接在這個 seq / hash 後面。 */
    @Volatile override var lastSeq: Long = 0
        private set
    @Volatile override var lastHash: String = Hashing.GENESIS
        private set

    /** 還留在本地、尚未確認上傳的筆數。 */
    @Volatile override var pendingCount: Long = 0
        private set

    init {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.autoCommit = true

        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
            // 刪除後把空頁還給檔案系統，否則 DB 檔只會停在歷史高水位。
            st.execute("PRAGMA auto_vacuum=INCREMENTAL")
            // WAL 檢查點後把檔案截回 8 MB，避免長時間執行後 -wal 檔無限長大。
            st.execute("PRAGMA journal_size_limit=8388608")

            st.execute(
                """
                CREATE TABLE IF NOT EXISTS outbox (
                    seq        INTEGER PRIMARY KEY,
                    doc_id     TEXT NOT NULL,
                    doc_json   TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """.trimIndent()
            )
            st.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
            // outbox 裡每一列都是待上傳，取批次就是 seq 順序掃，不需要任何二級索引。
            migrateLegacyArchive(st)
        }

        insertStmt = conn.prepareStatement(
            "INSERT INTO outbox(seq, doc_id, doc_json, created_at) VALUES(?,?,?,?)"
        )
        pendingStmt = conn.prepareStatement(
            "SELECT seq, doc_id, doc_json FROM outbox ORDER BY seq ASC LIMIT ?"
        )
        metaStmt = conn.prepareStatement(
            "INSERT INTO meta(k, v) VALUES(?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v"
        )

        restoreState()
    }

    /**
     * 舊版把所有紀錄永久留在 `records` 表（全量封存）。改成 outbox 後：
     * 還沒上傳的搬過來，已上傳的直接丟掉（ES 已經有了），然後整張表刪掉並 VACUUM。
     */
    private fun migrateLegacyArchive(st: Statement) {
        val hasLegacy = st.executeQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='records'"
        ).use { it.next() }
        if (!hasLegacy) return

        st.executeQuery("SELECT seq, record_sha256 FROM records ORDER BY seq DESC LIMIT 1").use { rs ->
            if (rs.next()) {
                val seq = rs.getLong(1)
                val hash = rs.getString(2)
                st.executeUpdate("INSERT OR REPLACE INTO meta(k, v) VALUES('chain_seq', '$seq')")
                st.executeUpdate("INSERT OR REPLACE INTO meta(k, v) VALUES('chain_hash', '$hash')")
            }
        }
        st.executeUpdate(
            "INSERT OR IGNORE INTO outbox(seq, doc_id, doc_json, created_at) " +
                "SELECT seq, doc_id, doc_json, created_at FROM records WHERE uploaded=0"
        )
        st.execute("DROP TABLE records")
        st.execute("VACUUM")   // 一次性：套用 auto_vacuum 設定並把舊資料佔的頁還回去
    }

    private fun restoreState() = synchronized(lock) {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT k, v FROM meta WHERE k IN ('chain_seq','chain_hash')").use { rs ->
                while (rs.next()) {
                    when (rs.getString(1)) {
                        "chain_seq" -> lastSeq = rs.getString(2).toLongOrNull() ?: 0
                        "chain_hash" -> lastHash = rs.getString(2)
                    }
                }
            }
            st.executeQuery("SELECT COUNT(*), COALESCE(MAX(seq), 0) FROM outbox").use { rs ->
                if (rs.next()) {
                    pendingCount = rs.getLong(1)
                    // 還沒送出去的 seq 一定要納入鏈尾，否則重啟後會發出重複的 seq。
                    lastSeq = maxOf(lastSeq, rs.getLong(2))
                }
            }
        }
    }

    override val droppedCount: Long get() = 0   // 落地模式不丟資料，積壓多少就留多少

    override val kind: SpoolKind get() = SpoolKind.DISK

    /**
     * 一個交易寫入整批，並在同一個交易裡推進鏈尾。
     * 原本一筆一個交易（還每次重新 prepare），高流量下 commit 次數就是瓶頸。
     */
    override fun insertAll(records: List<NewRecord>) = synchronized(lock) {
        if (records.isEmpty()) return@synchronized
        val now = Instant.now().toString()
        conn.autoCommit = false
        try {
            for (r in records) {
                insertStmt.setLong(1, r.seq)
                insertStmt.setString(2, r.docId)
                insertStmt.setString(3, r.docJson)
                insertStmt.setString(4, now)
                insertStmt.addBatch()
            }
            insertStmt.executeBatch()

            val last = records.last()
            writeMeta("chain_seq", last.seq.toString())
            writeMeta("chain_hash", last.recordHash)
            conn.commit()

            lastSeq = last.seq
            lastHash = last.recordHash
            pendingCount += records.size
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            insertStmt.clearBatch()
            conn.autoCommit = true
        }
    }

    private fun writeMeta(k: String, v: String) {
        metaStmt.setString(1, k)
        metaStmt.setString(2, v)
        metaStmt.executeUpdate()
    }

    override fun pendingBatch(limit: Int): List<Pending> = synchronized(lock) {
        val out = ArrayList<Pending>(minOf(limit, 512))
        pendingStmt.setInt(1, limit)
        pendingStmt.executeQuery().use { rs ->
            while (rs.next()) {
                val json = rs.getString(3)
                out.add(Pending(rs.getLong(1), rs.getString(2), json, json.toByteArray(Charsets.UTF_8).size))
            }
        }
        out
    }

    /**
     * 確認已進 ES 的紀錄直接刪除 —— 本地不保留副本，這就是「上傳即清空」的實作點。
     * seq 是 rowid，直接走主鍵刪除；回傳實際刪掉的筆數。
     */
    override fun purge(seqs: Collection<Long>): Int = synchronized(lock) {
        if (seqs.isEmpty()) return@synchronized 0
        // seq 是 Long，字串組裝沒有注入風險，而且一句 SQL 就拿得到精確刪除筆數。
        val inList = seqs.joinToString(",")
        val deleted = conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM outbox WHERE seq IN ($inList)")
        }
        if (deleted > 0) {
            pendingCount = (pendingCount - deleted).coerceAtLeast(0)
            // 把剛空出來的頁還給檔案系統；沒東西可回收時幾乎零成本。
            conn.createStatement().use { it.execute("PRAGMA incremental_vacuum") }
        }
        deleted
    }

    /** 本地實際佔用的位元組（含 WAL / shm）。 */
    override fun usageBytes(): Long {
        val base = File(dbPath)
        return listOf(base, File("$dbPath-wal"), File("$dbPath-shm"))
            .sumOf { if (it.exists()) it.length() else 0L }
    }

    override fun close() = synchronized(lock) {
        try { insertStmt.close() } catch (_: Exception) {}
        try { pendingStmt.close() } catch (_: Exception) {}
        try { metaStmt.close() } catch (_: Exception) {}
        // 關閉前把 WAL 併回主檔，下次啟動不用重播，檔案也小一點。
        try { conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
    }
}
