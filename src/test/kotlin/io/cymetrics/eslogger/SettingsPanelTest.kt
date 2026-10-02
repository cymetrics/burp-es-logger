package io.cymetrics.eslogger

import io.cymetrics.eslogger.config.Config
import io.cymetrics.eslogger.core.RecordWriter
import io.cymetrics.eslogger.fakes.FakeLogging
import io.cymetrics.eslogger.fakes.FakePreferences
import io.cymetrics.eslogger.fakes.RecordingSpool
import io.cymetrics.eslogger.ui.SettingsPanel
import io.cymetrics.eslogger.upload.ElasticUploader
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 面板只要能被建構出來就值得測。
 *
 * Kotlin 的屬性依宣告順序初始化，所以 init 區塊若用到宣告在它後面的屬性，
 * 編譯完全不會抱怨，執行時才 NPE —— 而這種錯誤只會在 Burp 載入擴充時出現，
 * 開發機上跑測試完全碰不到。
 */
class SettingsPanelTest {

    private fun buildPanel(): SettingsPanel {
        val prefs = FakePreferences()
        val config = Config(prefs).apply { testerId = "zet"; projectId = "demo"; save() }
        val spool = RecordingSpool()
        val logging = FakeLogging()
        return SettingsPanel(config, spool, ElasticUploader(config, spool, logging), RecordWriter(config, spool, logging))
    }

    @Test
    fun `the panel can be constructed and disposed`() {
        System.setProperty("java.awt.headless", "true")
        var built: SettingsPanel? = null
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                built = buildPanel()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw AssertionError("constructing the settings panel threw ${it}", it) }
        assertTrue(built != null)
        SwingUtilities.invokeAndWait { built!!.dispose() }
    }
}
