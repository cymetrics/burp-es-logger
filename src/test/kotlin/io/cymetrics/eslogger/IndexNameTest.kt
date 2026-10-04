package io.cymetrics.eslogger

import io.cymetrics.eslogger.config.Config
import io.cymetrics.eslogger.fakes.FakePreferences
import io.cymetrics.eslogger.fakes.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** index 名稱由使用者輸入的前綴與 Project ID 組成，必須收斂成 ES 接受的形式。 */
class IndexNameTest {

    @Test
    fun `uppercase is folded`() {
        assertEquals("burp-log-acme", Config.sanitizeIndex("Burp-Log-ACME"))
    }

    @Test
    fun `characters elasticsearch rejects are replaced`() {
        assertEquals("burp-log-a-b-c", Config.sanitizeIndex("burp-log-a b/c"))
    }

    @Test
    fun `leading characters elasticsearch forbids are stripped`() {
        assertEquals("acme", Config.sanitizeIndex("_acme"))
        assertEquals("acme", Config.sanitizeIndex("+acme"))
        assertEquals("acme", Config.sanitizeIndex(".acme"))
    }

    @Test
    fun `a blank prefix writes to the same index the settings preview shows`() {
        // 預覽一直顯示 burp-log-<project>；實際寫入若變成 <project>，就落在 template 與 API key 的範圍外。
        assertEquals("burp-log-acme", Config.indexNameFor("  ", "acme"))
        val config = testConfig().apply { indexPrefix = ""; save() }
        assertEquals(Config.indexNameFor("", "acme"), config.indexName())
    }

    @Test
    fun `saving keeps the in-memory settings identical to what a reload reads`() {
        val prefs = FakePreferences()
        val config = testConfig(prefs).apply {
            projectId = " acme "
            testerId = " zet "
            indexPrefix = " burp-log "
            save()
        }
        assertEquals("burp-log-acme", config.indexName())
        assertEquals("zet", config.testerId)
        val reloaded = Config(prefs)
        assertEquals(reloaded.indexName(), config.indexName())
        assertEquals(reloaded.projectId, config.projectId)
    }

    @Test
    fun `an empty project falls back to a usable name`() {
        assertEquals("burp-log-default", Config.sanitizeIndex(""))
        assertEquals("burp-log-default", Config.sanitizeIndex("___"))
    }
}
