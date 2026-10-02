package io.cymetrics.eslogger

import io.cymetrics.eslogger.config.Config
import io.cymetrics.eslogger.core.RecordWriter
import io.cymetrics.eslogger.fakes.FakeLogging
import io.cymetrics.eslogger.fakes.FakePreferences
import io.cymetrics.eslogger.fakes.RecordingSpool
import io.cymetrics.eslogger.fakes.testConfig
import io.cymetrics.eslogger.ui.SettingsPanel
import io.cymetrics.eslogger.upload.ElasticUploader
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        val config = testConfig(project = "demo")
        val spool = RecordingSpool()
        val logging = FakeLogging()
        return SettingsPanel(config, spool, ElasticUploader(config, spool, logging), RecordWriter(config, spool, logging))
    }

    @Test
    fun `the panel can be constructed and started`() {
        var built: SettingsPanel? = null
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                built = buildPanel().also { it.start() }
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw AssertionError("constructing the settings panel failed", it) }
        val panel = assertNotNull(built, "the panel was not constructed")
        try {
            // rebuild() 以 invokeLater 收尾，排空佇列才看得到那段的例外
            SwingUtilities.invokeAndWait { }
            assertTrue(panel.statsTimerRunning, "start() should have armed the stats timer")
        } finally {
            // 無論如何都要停掉計時器，否則它會在共用的測試 JVM 裡每 5 秒觸發到結束
            SwingUtilities.invokeAndWait { panel.dispose() }
        }
        // dispose 的全部意義就是停掉這個計時器 —— 不驗證的話，哪天它變成空殼也不會有人發現，
        // API key 就會跟著面板被留在記憶體裡
        assertFalse(panel.statsTimerRunning, "dispose() must stop the stats timer")
    }
}
