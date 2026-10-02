package io.cymetrics.eslogger

import io.cymetrics.eslogger.config.Config
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
    fun `an empty project falls back to a usable name`() {
        assertEquals("burp-log-default", Config.sanitizeIndex(""))
        assertEquals("burp-log-default", Config.sanitizeIndex("___"))
    }
}
