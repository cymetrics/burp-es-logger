package io.cymetrics.eslogger

import io.cymetrics.eslogger.fakes.FakeLogging
import io.cymetrics.eslogger.fakes.testConfig
import io.cymetrics.eslogger.storage.SpoolKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 落地模式的 DB 開不起來時，擴充仍要載入並繼續記錄。直接讓 initialize 失敗的話，
 * 整段測試期間什麼都不記，index 裡連缺號都不會留下。
 */
class SpoolFallbackTest {

    @Test
    fun `an unreadable SQLite spool falls back to memory and says so`() {
        val dir = Files.createTempDirectory("es-logger-fallback-")
        try {
            val db = dir.resolve("records.db")
            Files.writeString(db, "this is not an SQLite database")
            val config = testConfig(persistLocally = true).apply { dbPath = db.toString() }
            val logging = FakeLogging()

            val spool = ExtensionMain.openSpool(config, config.indexName(), logging)

            assertEquals(SpoolKind.MEMORY, spool.kind)
            assertTrue(logging.events.any { it.startsWith("CRITICAL") && "SQLite" in it })
            assertEquals("this is not an SQLite database", Files.readString(db), "the file must be left untouched")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a healthy SQLite spool is used as configured`() {
        val dir = Files.createTempDirectory("es-logger-fallback-")
        try {
            val config = testConfig(persistLocally = true).apply { dbPath = dir.resolve("records.db").toString() }
            val logging = FakeLogging()

            val spool = ExtensionMain.openSpool(config, config.indexName(), logging)
            try {
                assertEquals(SpoolKind.DISK, spool.kind)
                assertTrue(logging.events.isEmpty())
            } finally {
                spool.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
