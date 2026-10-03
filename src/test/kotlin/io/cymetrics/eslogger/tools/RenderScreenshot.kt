package io.cymetrics.eslogger.tools

import io.cymetrics.eslogger.core.RecordWriter
import io.cymetrics.eslogger.fakes.FakeLogging
import io.cymetrics.eslogger.fakes.RecordingSpool
import io.cymetrics.eslogger.fakes.testConfig
import io.cymetrics.eslogger.ui.SettingsPanel
import io.cymetrics.eslogger.upload.ElasticUploader
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Renders the settings panel straight to a PNG for the documentation.
 *
 * This is not a mock-up: it paints the extension's real UI code, so the
 * screenshot cannot drift from the actual panel. It also needs no running Burp,
 * which keeps real endpoints, client names and API keys out of the image.
 *
 *   JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew renderScreenshot
 */
object RenderScreenshot {

    @JvmStatic
    fun main(args: Array<String>) {
        val lang = args.getOrNull(0) ?: "en"
        val out = File(args.getOrNull(1) ?: "docs/screenshot.png")
        val width = args.getOrNull(2)?.toIntOrNull() ?: 1180
        val height = args.getOrNull(3)?.toIntOrNull() ?: 1150

        SwingUtilities.invokeAndWait {
            val config = testConfig(tester = "zet", project = "demo").apply {
                esEndpoint = "https://example-project.es.us-east-1.aws.elastic.cloud:443"
                esApiKey = "ZXhhbXBsZS1rZXktbm90LXJlYWwtZG8tbm90LXVzZQ=="
                uiLang = lang
                save()
            }
            val spool = RecordingSpool()
            val logging = FakeLogging()
            val panel = SettingsPanel(
                config, spool,
                ElasticUploader(config, spool, logging),
                RecordWriter(config, spool, logging)
            )

            // A realised frame is what gives the panel real fonts and metrics.
            val frame = JFrame().apply {
                isUndecorated = true
                contentPane = panel
                setSize(width, height)
            }
            panel.start()
            frame.isVisible = true
            frame.validate()

            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            panel.paint(g)
            g.dispose()

            out.parentFile?.mkdirs()
            ImageIO.write(image, "png", out)
            println("wrote ${out.path} (${width}x$height)")

            panel.dispose()
            frame.dispose()
        }
    }
}
