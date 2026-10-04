package io.cymetrics.eslogger.storage

import com.google.gson.JsonParser
import io.cymetrics.eslogger.integrity.Hashing
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant

/**
 * 本地 outbox：確認進入 ES 就刪除，鏈尾與未送出的紀錄依目標 index 分開保存。
 * 同一個 DB 可在不同專案間重用，各 index 的 seq、purge 與鏈尾互不影響。
 * 單一連線 + 鎖讓 writer、uploader 的交易互斥；UI 讀取記憶體計數器。
 *
 * 舊版 DB 沒有目標 index。只有 [legacyTip] 與舊鏈尾完全相符、且所有待送文件的
 * project_id 都符合 [legacyProjectId] 時才認領舊資料；
 * 不能辨識的資料保留在 legacy 表，不能把它猜成目前專案並上傳。
 */
class SqliteStore(
    private val dbPath: String,
    private val targetIndex: String,
    legacyTip: Pair<Long, String>? = null,
    legacyProjectId: String? = null
) : RecordSpool {

    private val conn: Connection
    private val lock = Any()
    private val insertStmt: PreparedStatement
    private val pendingStmt: PreparedStatement
    private val metaStmt: PreparedStatement

    @Volatile override var lastSeq: Long = 0
        private set
    @Volatile override var lastHash: String = Hashing.GENESIS
        private set
    @Volatile override var pendingCount: Long = 0
        private set

    /** 尚無法確認屬於哪個 index 的舊版積壓，保留但不會由這個實例上傳或刪除。 */
    val legacyPendingCount: Long

    init {
        require(targetIndex.isNotBlank())
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        try {
            conn.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout=5000")
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA synchronous=NORMAL")
                st.execute("PRAGMA auto_vacuum=INCREMENTAL")
                st.execute("PRAGMA journal_size_limit=8388608")
            }
            initializeSchema(legacyTip, legacyProjectId)
            insertStmt = conn.prepareStatement(
                "INSERT INTO outbox(target_index, seq, doc_id, doc_json, byte_size, created_at) VALUES(?,?,?,?,?,?)"
            )
            pendingStmt = conn.prepareStatement(
                "SELECT seq, doc_id, byte_size, doc_json FROM outbox WHERE target_index=? ORDER BY seq ASC LIMIT ?"
            )
            metaStmt = conn.prepareStatement(
                "INSERT INTO meta(target_index, k, v) VALUES(?, ?, ?) " +
                    "ON CONFLICT(target_index, k) DO UPDATE SET v=excluded.v"
            )
            restoreState()
            legacyPendingCount = countLegacyPending()
        } catch (t: Throwable) {
            try { conn.close() } catch (_: Exception) {}
            throw t
        }
    }

    private fun tableExists(name: String): Boolean = conn.prepareStatement(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?"
    ).use { st ->
        st.setString(1, name)
        st.executeQuery().use { it.next() }
    }

    /** 表名只有本類別的常數會傳入，不接受設定值。 */
    private fun hasTargetColumn(table: String): Boolean = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info($table)").use { rs ->
            var found = false
            while (rs.next()) if (rs.getString("name") == "target_index") found = true
            found
        }
    }

    /** 包含改名、複製、鏈尾與刪除舊表在內的單一交易，失敗會完整回復。 */
    private fun initializeSchema(legacyTip: Pair<Long, String>?, legacyProjectId: String?) {
        conn.autoCommit = false
        try {
            conn.createStatement().use { st ->
                for (table in listOf("outbox", "meta")) {
                    if (tableExists(table) && !hasTargetColumn(table)) {
                        st.execute("ALTER TABLE $table RENAME TO legacy_$table")
                    }
                }
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS outbox (
                        target_index TEXT NOT NULL,
                        seq          INTEGER NOT NULL,
                        doc_id       TEXT NOT NULL,
                        doc_json     TEXT NOT NULL,
                        byte_size    INTEGER NOT NULL,
                        created_at   TEXT NOT NULL,
                        PRIMARY KEY(target_index, seq)
                    )
                    """.trimIndent()
                )
                st.execute(
                    "CREATE TABLE IF NOT EXISTS meta (" +
                        "target_index TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, " +
                        "PRIMARY KEY(target_index, k))"
                )
            }
            val oldTip = legacyChainTip()
            val claimed = oldTip != null && oldTip.first > 0 && legacyTip == oldTip &&
                legacyProjectId != null && legacyRowsMatchProject(legacyProjectId)
            if (claimed) claimLegacy(oldTip)
            conn.commit()
            if (claimed) reclaimSpace()
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    /**
     * 舊表刪掉後做一次 VACUUM：封存時代的 DB 建立時沒有 auto_vacuum，之後每次 purge 的
     * incremental_vacuum 都不會生效，檔案會永遠停在舊表的大小。VACUUM 不能在交易裡跑，
     * 所以放在 commit 之後；失敗只影響檔案大小，不影響資料。
     */
    private fun reclaimSpace() {
        conn.autoCommit = true
        try {
            conn.createStatement().use { it.execute("VACUUM") }
        } catch (_: Exception) {
        }
    }

    /** 鏈尾相符仍不保證舊積壓只有一個專案；混雜、缺欄位或無法解析時整批保留。 */
    private fun legacyRowsMatchProject(project: String): Boolean {
        for (source in listOf("legacy_outbox", "records")) {
            if (!tableExists(source)) continue
            val filter = if (source == "records") " WHERE uploaded=0" else ""
            conn.createStatement().use { st ->
                st.executeQuery("SELECT doc_json FROM $source$filter").use { rs ->
                    while (rs.next()) {
                        val matches = try {
                            val field = JsonParser.parseString(rs.getString(1)).asJsonObject.get("project_id")
                            field != null && field.isJsonPrimitive && field.asJsonPrimitive.isString &&
                                field.asString == project
                        } catch (_: Exception) { false }
                        if (!matches) return false
                    }
                }
            }
        }
        return true
    }

    private fun legacyChainTip(): Pair<Long, String>? {
        var tip: Pair<Long, String>? = null
        if (tableExists("legacy_meta")) {
            val values = mutableMapOf<String, String>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT k, v FROM legacy_meta WHERE k IN ('chain_seq','chain_hash')").use { rs ->
                    while (rs.next()) values[rs.getString(1)] = rs.getString(2)
                }
            }
            val seq = values["chain_seq"]?.toLongOrNull()
            val hash = values["chain_hash"]
            if (seq != null && hash != null) tip = seq to hash
        }
        if (tableExists("records")) {
            conn.createStatement().use { st ->
                st.executeQuery("SELECT seq, record_sha256 FROM records ORDER BY seq DESC LIMIT 1").use { rs ->
                    if (rs.next()) {
                        val archiveTip = rs.getLong(1) to rs.getString(2)
                        // 相同序號卻不同 hash 不能認領，保留給人工復原。
                        if (tip?.first == archiveTip.first && tip != archiveTip) return null
                        if (tip == null || archiveTip.first > tip!!.first) tip = archiveTip
                    }
                }
            }
        }
        return tip
    }

    private fun claimLegacy(tip: Pair<Long, String>) {
        for (source in listOf("legacy_outbox", "records")) {
            if (!tableExists(source)) continue
            val filter = if (source == "records") " AND old.uploaded=0" else ""
            // 支援舊版遷移中斷後兩張表暫時並存，但絕不以 IGNORE 吃掉內容不同的同序號資料。
            conn.prepareStatement(
                "SELECT 1 FROM $source old JOIN outbox current " +
                    "ON current.target_index=? AND current.seq=old.seq " +
                    "WHERE (current.doc_id IS NOT old.doc_id OR current.doc_json IS NOT old.doc_json)$filter LIMIT 1"
            ).use { st ->
                st.setString(1, targetIndex)
                check(!st.executeQuery().use { it.next() }) { "Legacy spool conflicts with target $targetIndex; data preserved" }
            }
            conn.prepareStatement(
                "INSERT INTO outbox(target_index, seq, doc_id, doc_json, byte_size, created_at) " +
                    "SELECT ?, old.seq, old.doc_id, old.doc_json, length(CAST(old.doc_json AS BLOB)), old.created_at " +
                    "FROM $source old WHERE 1=1$filter AND NOT EXISTS " +
                    "(SELECT 1 FROM outbox current WHERE current.target_index=? AND current.seq=old.seq)"
            ).use { st ->
                st.setString(1, targetIndex)
                st.setString(2, targetIndex)
                st.executeUpdate()
            }
        }
        val currentSeq = conn.prepareStatement(
            "SELECT v FROM meta WHERE target_index=? AND k='chain_seq'"
        ).use { st ->
            st.setString(1, targetIndex)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1).toLongOrNull() ?: 0L else 0L }
        }
        if (tip.first == currentSeq) {
            val currentHash = conn.prepareStatement(
                "SELECT v FROM meta WHERE target_index=? AND k='chain_hash'"
            ).use { st ->
                st.setString(1, targetIndex)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
            check(currentHash == tip.second) { "Legacy chain conflicts with target $targetIndex; data preserved" }
        }
        if (tip.first >= currentSeq) {
            conn.prepareStatement(
                "INSERT INTO meta(target_index, k, v) VALUES(?,?,?) " +
                    "ON CONFLICT(target_index,k) DO UPDATE SET v=excluded.v"
            ).use { st ->
                for ((key, value) in listOf("chain_seq" to tip.first.toString(), "chain_hash" to tip.second)) {
                    st.setString(1, targetIndex)
                    st.setString(2, key)
                    st.setString(3, value)
                    st.executeUpdate()
                }
            }
        }
        conn.createStatement().use { st ->
            st.execute("DROP TABLE IF EXISTS legacy_outbox")
            st.execute("DROP TABLE IF EXISTS legacy_meta")
            st.execute("DROP TABLE IF EXISTS records")
        }
    }

    private fun countLegacyPending(): Long {
        val sources = mutableListOf<String>()
        if (tableExists("legacy_outbox")) sources.add("SELECT seq FROM legacy_outbox")
        if (tableExists("records")) sources.add("SELECT seq FROM records WHERE uploaded=0")
        if (sources.isEmpty()) return 0
        return conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM (${sources.joinToString(" UNION ")})").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun restoreState() = synchronized(lock) {
        conn.prepareStatement("SELECT k, v FROM meta WHERE target_index=?").use { st ->
            st.setString(1, targetIndex)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    when (rs.getString(1)) {
                        "chain_seq" -> lastSeq = rs.getString(2).toLongOrNull() ?: 0
                        "chain_hash" -> lastHash = rs.getString(2)
                    }
                }
            }
        }
        conn.prepareStatement("SELECT COUNT(*), COALESCE(MAX(seq),0) FROM outbox WHERE target_index=?").use { st ->
            st.setString(1, targetIndex)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    pendingCount = rs.getLong(1)
                    lastSeq = maxOf(lastSeq, rs.getLong(2))
                }
            }
        }
    }

    override val droppedCount: Long get() = 0
    override val kind: SpoolKind get() = SpoolKind.DISK

    override fun insertAll(records: List<NewRecord>) = synchronized(lock) {
        if (records.isEmpty()) return@synchronized
        val now = Instant.now().toString()
        conn.autoCommit = false
        try {
            for (r in records) {
                insertStmt.setString(1, targetIndex)
                insertStmt.setLong(2, r.seq)
                insertStmt.setString(3, r.docId)
                insertStmt.setString(4, r.docJson)
                insertStmt.setInt(5, r.byteSize)
                insertStmt.setString(6, now)
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
        metaStmt.setString(1, targetIndex)
        metaStmt.setString(2, k)
        metaStmt.setString(3, v)
        metaStmt.executeUpdate()
    }

    override fun pendingBatch(limit: Int, maxBytes: Long): List<Pending> = synchronized(lock) {
        require(limit >= 0 && maxBytes >= 0)
        val out = ArrayList<Pending>(minOf(limit, 512))
        var bytes = 0L
        pendingStmt.setString(1, targetIndex)
        pendingStmt.setInt(2, limit)
        pendingStmt.executeQuery().use { rs ->
            while (rs.next()) {
                val size = rs.getInt(3)
                // 先讀已保存的位元組數，確認放得下才從 JDBC 解碼這筆 JSON。
                if (out.isNotEmpty() && size > maxBytes - bytes) break
                out.add(Pending(rs.getLong(1), rs.getString(2), rs.getString(4), size))
                bytes += size
                if (bytes >= maxBytes) break
            }
        }
        out
    }

    override fun purge(seqs: Collection<Long>): Int = synchronized(lock) {
        if (seqs.isEmpty()) return@synchronized 0
        val inList = seqs.joinToString(",")
        val deleted = conn.prepareStatement("DELETE FROM outbox WHERE target_index=? AND seq IN ($inList)").use { st ->
            st.setString(1, targetIndex)
            st.executeUpdate()
        }
        if (deleted > 0) {
            pendingCount = (pendingCount - deleted).coerceAtLeast(0)
            conn.createStatement().use { it.execute("PRAGMA incremental_vacuum") }
        }
        deleted
    }

    /** 整個 DB 的實際大小，包含其他專案與尚未認領的舊版資料。 */
    override fun usageBytes(): Long = listOf(File(dbPath), File("$dbPath-wal"), File("$dbPath-shm"))
        .sumOf { if (it.exists()) it.length() else 0L }

    override fun close() = synchronized(lock) {
        try { insertStmt.close() } catch (_: Exception) {}
        try { pendingStmt.close() } catch (_: Exception) {}
        try { metaStmt.close() } catch (_: Exception) {}
        try { conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
    }
}
