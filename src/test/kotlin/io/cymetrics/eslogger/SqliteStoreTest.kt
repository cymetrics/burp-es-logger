package io.cymetrics.eslogger

import io.cymetrics.eslogger.integrity.Hashing
import io.cymetrics.eslogger.storage.NewRecord
import io.cymetrics.eslogger.storage.SqliteStore
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteStoreTest {
    private fun record(seq: Long, payload: String = """{"seq":$seq}""", id: String = "doc-$seq") =
        NewRecord(seq, id, "hash-$id", payload, payload.toByteArray(Charsets.UTF_8).size)

    private fun withDatabase(block: (String) -> Unit) {
        val dir = Files.createTempDirectory("es-logger-sqlite-test-")
        try {
            Class.forName("org.sqlite.JDBC")
            block(dir.resolve("spool.db").toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun withStore(
        path: String,
        index: String,
        legacyTip: Pair<Long, String>? = null,
        legacyProjectId: String? = "a",
        block: (SqliteStore) -> Unit
    ) {
        val store = SqliteStore(path, index, legacyTip, legacyProjectId)
        try { block(store) } finally { store.close() }
    }

    @Test
    fun `switching projects after purge and reload preserves independent chain tips`() = withDatabase { db ->
        withStore(db, "logs-a") { a ->
            a.insertAll(listOf(record(1), record(2)))
            a.purge(listOf(1, 2))
        }
        withStore(db, "logs-b") { b ->
            assertEquals(0, b.lastSeq)
            assertEquals(Hashing.GENESIS, b.lastHash)
            assertEquals(0, b.pendingCount)
            b.insertAll(listOf(record(1, id = "b-1")))
        }
        withStore(db, "logs-a") { a ->
            assertEquals(2, a.lastSeq)
            assertEquals("hash-doc-2", a.lastHash)
            assertEquals(0, a.pendingCount)
            a.insertAll(listOf(record(3)))
        }
        withStore(db, "logs-b") { b ->
            assertEquals(1, b.lastSeq)
            assertEquals("hash-b-1", b.lastHash)
            assertEquals(listOf("b-1"), b.pendingBatch(10).map { it.docId })
        }
    }

    @Test
    fun `same sequence in different indexes does not collide or purge another index`() = withDatabase { db ->
        withStore(db, "logs-a") { it.insertAll(listOf(record(1, id = "a-1"))) }
        withStore(db, "logs-b") { b ->
            b.insertAll(listOf(record(1, id = "b-1")))
            assertEquals(listOf("b-1"), b.pendingBatch(10).map { it.docId })
            assertEquals(1, b.purge(listOf(1)))
        }
        withStore(db, "logs-a") { a ->
            assertEquals(1, a.pendingCount)
            assertEquals(listOf("a-1"), a.pendingBatch(10).map { it.docId })
        }
    }

    @Test
    fun `byte budget bounds large payloads and still returns an oversized first row`() = withDatabase { db ->
        val payload = "測".repeat(128 * 1024)
        val row = record(1, payload)
        withStore(db, "logs-a") { store ->
            store.insertAll((1L..20L).map { record(it, payload) })
            assertEquals(listOf(1L, 2L), store.pendingBatch(500, row.byteSize * 2L).map { it.seq })
            assertEquals(listOf(1L), store.pendingBatch(500, row.byteSize - 1L).map { it.seq })
            assertEquals(listOf(1L), store.pendingBatch(500, 0).map { it.seq })
            assertEquals(row.byteSize, store.pendingBatch(1).single().byteSize)
            assertTrue(store.pendingBatch(0).isEmpty())
            assertEquals(20, store.pendingCount)
        }
    }

    @Test
    fun `empty insert preserves the current chain and pending count`() = withDatabase { db ->
        withStore(db, "logs-a") { store ->
            store.insertAll(listOf(record(1)))
            store.insertAll(emptyList())
            assertEquals(1, store.lastSeq)
            assertEquals("hash-doc-1", store.lastHash)
            assertEquals(1, store.pendingCount)
        }
    }

    private fun legacyOutbox(db: String) {
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE outbox(seq INTEGER PRIMARY KEY, doc_id TEXT NOT NULL, doc_json TEXT NOT NULL, created_at TEXT NOT NULL)")
                st.execute("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
                st.execute("INSERT INTO outbox VALUES(7,'original-id','{\"project_id\":\"a\",\"payload\":\"測試\"}','2026-01-01')")
                st.execute("INSERT INTO meta VALUES('chain_seq','9'),('chain_hash','original-hash')")
            }
        }
    }

    @Test
    fun `matching legacy chain proof migrates backlog without changing identity or payload`() = withDatabase { db ->
        legacyOutbox(db)
        withStore(db, "logs-a", 9L to "original-hash") { store ->
            val row = store.pendingBatch(10).single()
            assertEquals(7, row.seq)
            assertEquals("original-id", row.docId)
            assertEquals("""{"project_id":"a","payload":"測試"}""", row.docJson)
            assertEquals(row.docJson.toByteArray(Charsets.UTF_8).size, row.byteSize)
            assertEquals(9, store.lastSeq)
            assertEquals("original-hash", store.lastHash)
            assertEquals(0, store.legacyPendingCount)
        }
        withStore(db, "logs-a") { store ->
            assertEquals(9, store.lastSeq)
            assertEquals(1, store.pendingCount)
        }
    }

    @Test
    fun `unknown legacy target is preserved until the original target supplies matching proof`() = withDatabase { db ->
        legacyOutbox(db)
        withStore(db, "logs-b", 9L to "different-hash") { b ->
            assertEquals(0, b.lastSeq)
            assertEquals(Hashing.GENESIS, b.lastHash)
            assertEquals(0, b.pendingCount)
            assertEquals(1, b.legacyPendingCount)
            assertTrue(b.pendingBatch(500).isEmpty())
            assertEquals(0, b.purge(listOf(7)))
            b.insertAll(listOf(record(1, id = "b-1")))
        }
        withStore(db, "logs-a") { unknown ->
            assertEquals(1, unknown.legacyPendingCount)
            assertEquals(0, unknown.pendingCount)
        }
        withStore(db, "logs-a", 9L to "original-hash") { a ->
            assertEquals(9, a.lastSeq)
            assertEquals(listOf("original-id"), a.pendingBatch(10).map { it.docId })
            assertEquals(0, a.legacyPendingCount)
        }
        withStore(db, "logs-b") { b ->
            assertEquals(1, b.lastSeq)
            assertEquals(listOf("b-1"), b.pendingBatch(10).map { it.docId })
        }
    }

    @Test
    fun `archive migration retains pending records and latest chain tip only after matching proof`() = withDatabase { db ->
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE records(seq INTEGER PRIMARY KEY, doc_id TEXT, doc_json TEXT, created_at TEXT, record_sha256 TEXT, uploaded INTEGER)")
                st.execute("INSERT INTO records VALUES(1,'pending-id','{\"project_id\":\"a\"}','2026-01-01','hash-1',0)")
                st.execute("INSERT INTO records VALUES(2,'uploaded-id','{\"project_id\":\"a\"}','2026-01-01','hash-2',1)")
            }
        }
        withStore(db, "logs-b") { b ->
            assertEquals(1, b.legacyPendingCount)
            assertEquals(0, b.pendingCount)
        }
        withStore(db, "logs-a", 2L to "hash-2") { a ->
            assertEquals(listOf("pending-id"), a.pendingBatch(10).map { it.docId })
            assertEquals(2, a.lastSeq)
            assertEquals("hash-2", a.lastHash)
            assertEquals(0, a.legacyPendingCount)
        }
    }

    @Test
    fun `claiming an archive-era database lets later purges shrink the file`() = withDatabase { db ->
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE records(seq INTEGER PRIMARY KEY, doc_id TEXT, doc_json TEXT, created_at TEXT, record_sha256 TEXT, uploaded INTEGER)")
                st.execute("INSERT INTO records VALUES(1,'uploaded-id','{\"project_id\":\"a\"}','2026-01-01','hash-1',1)")
            }
        }
        withStore(db, "logs-a", 1L to "hash-1") { assertEquals(0, it.legacyPendingCount) }
        // auto_vacuum 只能在建表前設定，或靠一次 VACUUM 套用；否則 purge 的 incremental_vacuum 永遠無效。
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA auto_vacuum").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(2, rs.getInt(1), "expected INCREMENTAL auto_vacuum after migration")
                }
            }
        }
    }

    @Test
    fun `conflicting migration rolls back and preserves both existing and legacy payloads`() = withDatabase { db ->
        legacyOutbox(db)
        withStore(db, "logs-a") { a ->
            a.insertAll(listOf(record(7, id = "new-id")))
        }
        assertFailsWith<IllegalStateException> { SqliteStore(db, "logs-a", 9L to "original-hash", "a") }
        withStore(db, "logs-a") { a ->
            assertEquals(1, a.legacyPendingCount)
            assertEquals(listOf("new-id"), a.pendingBatch(10).map { it.docId })
            assertEquals(7, a.lastSeq)
        }
        withStore(db, "recovered-original-index", 9L to "original-hash") { a ->
            assertEquals(listOf("original-id"), a.pendingBatch(10).map { it.docId })
        }
    }

    @Test
    fun `legacy data needs both project identity and matching tip`() = withDatabase { db ->
        legacyOutbox(db)
        for (project in listOf(null, "b")) {
            withStore(db, "logs-a", 9L to "original-hash", project) { store ->
                assertEquals(1, store.legacyPendingCount)
                assertEquals(0, store.pendingCount)
                assertEquals(0, store.lastSeq)
            }
        }
        withStore(db, "logs-a", 9L to "original-hash", "a") { store ->
            assertEquals(1, store.pendingCount)
        }
    }

    @Test
    fun `mixed or unidentifiable legacy projects stay quarantined despite a matching tip`() {
        for (payload in listOf("{\"project_id\":\"b\"}", "{}", "not json")) withDatabase { db ->
            legacyOutbox(db)
            DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
                conn.prepareStatement("INSERT INTO outbox VALUES(8,'other-id',?,'2026-01-01')").use { st ->
                    st.setString(1, payload)
                    st.executeUpdate()
                }
            }
            withStore(db, "logs-a", 9L to "original-hash", "a") { store ->
                assertEquals(2, store.legacyPendingCount)
                assertEquals(0, store.pendingCount)
                assertEquals(0, store.lastSeq)
            }
        }
    }

    @Test
    fun `legacy archive constraint violation rolls back instead of silently deleting a row`() = withDatabase { db ->
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE records(seq INTEGER PRIMARY KEY, doc_id TEXT, doc_json TEXT, created_at TEXT, record_sha256 TEXT, uploaded INTEGER)")
                st.execute("INSERT INTO records VALUES(1,'pending-id','{\"project_id\":\"a\"}',NULL,'hash-1',0)")
            }
        }
        assertFailsWith<SQLException> { SqliteStore(db, "logs-a", 1L to "hash-1", "a") }
        withStore(db, "logs-a") { store ->
            assertEquals(1, store.legacyPendingCount)
            assertEquals(0, store.pendingCount)
        }
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT doc_id, created_at FROM records").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("pending-id", rs.getString(1))
                    assertEquals(null, rs.getString(2))
                }
            }
        }
    }

    @Test
    fun `conflicting same-sequence chain tip never overwrites a scoped tip`() = withDatabase { db ->
        legacyOutbox(db)
        withStore(db, "logs-a") { store ->
            store.insertAll(listOf(record(9, id = "scoped-9")))
            store.purge(listOf(9))
        }
        assertFailsWith<IllegalStateException> { SqliteStore(db, "logs-a", 9L to "original-hash", "a") }
        withStore(db, "logs-a") { store ->
            assertEquals(9, store.lastSeq)
            assertEquals("hash-scoped-9", store.lastHash)
            assertEquals(1, store.legacyPendingCount)
            assertEquals(0, store.pendingCount)
        }
    }
}
