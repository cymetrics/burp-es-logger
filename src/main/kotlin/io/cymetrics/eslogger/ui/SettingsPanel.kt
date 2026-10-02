package io.cymetrics.eslogger.ui

import io.cymetrics.eslogger.config.Config
import io.cymetrics.eslogger.core.RecordWriter
import io.cymetrics.eslogger.storage.RecordSpool
import io.cymetrics.eslogger.storage.SpoolKind
import io.cymetrics.eslogger.upload.ElasticUploader
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Font
import java.awt.LayoutManager
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * ES Logger 的設定 / 狀態分頁。
 *
 * Burp 特有的限制，改這支檔案前先看懂：
 *
 *  1. **不要用 "<html>…" 排版。** Burp 全域停用了 Swing 的 HTML 渲染（避免被記錄到的
 *     流量內容做 HTML injection），標籤會把標記原文印出來。粗體 / 字級一律用
 *     [Font.deriveFont]。
 *  2. **不要寫死顏色。** Burp 可切淺色 / 深色佈景，顏色一律從 [UIManager] 取，
 *     只有語意色（成功 / 警告 / 錯誤）用兩種佈景都看得清的中間調。
 *  3. **分頁寬度完全由使用者決定**（半個螢幕到 4K 全寬都有可能），所以版面自己做 RWD：
 *     - 內容維持一條寬度上限 [COLUMN_WIDTH] 的欄，寬螢幕時靠 [applyGutters] 置中。
 *     - 表單用 [ResponsiveForm]，窄的時候標籤改壓在欄位上方。
 *     - 說明文字用 [WrapText]（JTextArea 偽裝成標籤），會真的折行而不是被切掉。
 *     - 狀態磚與按鈕用 [WrapLayout]，窄視窗時換行而不是被裁掉。
 *
 * 文案全部來自 [Strings]；切換語言時整個版面重建，輸入欄位本身是成員所以值會留著。
 */
class SettingsPanel(
    private val config: Config,
    private val store: RecordSpool,
    private val uploader: ElasticUploader,
    private val writer: RecordWriter
) : JPanel(BorderLayout()) {

    private var s: Strings = Strings.of(Lang.from(config.uiLang))

    // ---------- 輸入欄位（跨語言重建時沿用同一批實例，值不會掉） ----------
    private val tfEndpoint = JTextField(config.esEndpoint, 30)
    private val tfApiKey = JPasswordField(config.esApiKey, 30)
    private val cbShowKey = JCheckBox()
    private val tfIndexPrefix = JTextField(config.indexPrefix, 16)
    private val tfTester = JTextField(config.testerId, 16)
    private val tfProject = JTextField(config.projectId, 16)
    private val tfExcluded = JTextField(config.excludedExtensions.joinToString(","), 30)
    private val tfMaxBody = JTextField(config.maxStoredBodyBytes.toString(), 10)
    private val tfInterval = JTextField(config.uploadIntervalSeconds.toString(), 5)
    private val tfBatch = JTextField(config.uploadBatchSize.toString(), 5)
    private val tfReqTimeout = JTextField(config.requestTimeoutSeconds.toString(), 5)
    private val cbStoreBodies = JCheckBox("", config.storeBodies)
    private val cbWs = JCheckBox("", config.captureWebSockets)
    private val cbFast = JCheckBox("", config.fastMode)
    private val cbUpload = JCheckBox("", config.uploadEnabled)
    private val cbPersist = JCheckBox("", config.persistLocally)
    private val langBox = JComboBox(Lang.entries.toTypedArray())

    private val defaultEcho = tfApiKey.echoChar

    // ---------- 衍生說明 / 狀態 ----------
    private val lblIndexPreview = muted("")
    private val lblBodyHint = muted("")
    private val lblStatus = WrapText({ baseFont }, { labelFg })
    private val dot = ThemedLabel("●", { baseFont }, { toneColor(statusTone) })

    private val statUploaded = StatTile()
    private val statPending = StatTile()
    private val statQueue = StatTile()
    private val statSpool = StatTile()
    private val statLast = StatTile()

    private var dirty = false
    private var lastSeenError = ""
    private var statusText: (Strings) -> String = { "" }
    private var statusTone = Tone.IDLE

    // 三個區塊的左右留白會隨視窗寬度改變，把內容維持成一個置中的欄。
    private lateinit var headerPanel: JPanel
    private lateinit var contentPanel: JPanel
    private lateinit var footerPanel: JPanel
    private lateinit var scroll: JScrollPane

    /** 內容區用基準底色，標題列與狀態列才有東西可以浮出來。 */
    override fun paintComponent(g: Graphics) {
        g.color = surfaceBg
        g.fillRect(0, 0, width, height)
        super.paintComponent(g)
    }

    init {
        isOpaque = false
        langBox.selectedItem = Lang.from(config.uiLang)
        langBox.addActionListener {
            val chosen = langBox.selectedItem as? Lang ?: return@addActionListener
            if (chosen.code == config.uiLang) return@addActionListener
            config.uiLang = chosen.code
            config.save()
            s = Strings.of(chosen)
            rebuild()
        }
        cbShowKey.addActionListener {
            tfApiKey.echoChar = if (cbShowKey.isSelected) 0.toChar() else defaultEcho
        }
        wireEditListeners()

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyGutters()
        })

        rebuild()
        startStatsTimer()
        showInitialStatus()
    }

    /** 重新套用文案並重建整個版面（語言切換時用）。 */
    private fun rebuild() {
        applyTexts()
        removeAll()
        headerPanel = buildHeader()
        scroll = buildForm()
        footerPanel = buildFooter()
        add(headerPanel, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(footerPanel, BorderLayout.SOUTH)
        applyGutters()
        updateDerivedLabels()
        refreshStats()
        renderStatus()
        revalidate()
        repaint()
        SwingUtilities.invokeLater { scroll.verticalScrollBar.value = 0 }
    }

    /** 成員元件的文字（勾選框、磚塊標題）不會隨版面重建而更新，要另外套。 */
    private fun applyTexts() {
        cbShowKey.text = s.showKey
        cbStoreBodies.text = s.storeBodies
        cbWs.text = s.captureWs
        cbFast.text = s.fastMode
        cbUpload.text = s.autoUpload
        cbPersist.text = s.persistLocal
        statUploaded.caption = s.tileUploaded
        statPending.caption = s.tilePending
        statQueue.caption = s.tileQueue
        statSpool.caption = if (store.kind == SpoolKind.MEMORY) s.tileSpoolMemory else s.tileSpoolDisk
        statLast.caption = s.tileLastUpload
    }

    private fun showInitialStatus() {
        if (config.esEndpoint.isBlank() || config.esApiKey.isBlank()) {
            setStatus(Tone.WARN) { it.statusNotConfigured }
        } else {
            setStatus(Tone.IDLE) { it.loaded(config.indexName()) }
        }
    }

    // ================= 版面 =================

    private fun buildHeader(): JPanel {
        val title = JLabel("ES Logger").apply {
            font = baseFont.deriveFont(Font.BOLD, baseFont.size2D + 5f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val subtitle = muted(s.subtitle).apply { alignmentX = Component.LEFT_ALIGNMENT }

        val text = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(title)
            add(Box.createVerticalStrut(4))
            add(subtitle)
        }

        val lang = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(ThemedLabel(s.langLabel, { baseFont.deriveFont(baseFont.size2D - 1f) }, { mutedFg }))
            add(langBox)
        }

        return object : JPanel(BorderLayout()) {
            // 自己畫而不是設 background：Burp 的 applyThemeToComponent 會覆寫 background，
            // 而且每次重繪都重新取色，使用者切換佈景時會跟著走。
            override fun paintComponent(g: Graphics) {
                g.color = chromeBg
                g.fillRect(0, 0, width, height)
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            // 放 CENTER 而不是 WEST：WEST 只給元件自己的 preferred width，副標題就不會折行。
            add(text, BorderLayout.CENTER)
            add(lang, BorderLayout.EAST)
        }
    }

    private fun buildForm(): JScrollPane {
        val conn = form().apply {
            row(s.endpoint, tfEndpoint, s.endpointHint)
            row(s.apiKey, apiKeyRow(), s.apiKeyHint)
            row(s.indexPrefix, tfIndexPrefix, stretch = false)
            note(lblIndexPreview)
        }

        val ident = form().apply {
            row(s.testerId, tfTester, stretch = false)
            row(s.projectId, tfProject, s.projectHint, stretch = false)
        }

        val capture = form().apply {
            row(s.excludedExt, tfExcluded, s.excludedHint)
            row(s.maxBody, withUnit(tfMaxBody, s.unitBytes), stretch = false)
            note(lblBodyHint)
            row(s.reqTimeout, withUnit(tfReqTimeout, s.unitSeconds), s.reqTimeoutHint, stretch = false)
            span(cbStoreBodies)
            span(cbWs)
            span(cbFast)
            checkboxNote(muted(s.fastModeHint))
        }

        val upload = form().apply {
            row(s.uploadInterval, withUnit(tfInterval, s.unitSeconds), s.uploadIntervalHint, stretch = false)
            row(s.batchSize, withUnit(tfBatch, s.unitRecords), s.batchSizeHint, stretch = false)
            span(cbUpload)
            span(cbPersist)
            checkboxNote(muted(s.persistLocalHint))
        }

        // 四個區塊共用一條標籤欄，欄位左緣才會對齊成一直線
        val forms = listOf(conn, ident, capture, upload)
        val sharedLabelWidth = { forms.maxOf { it.intrinsicLabelWidth() } }
        forms.forEach { it.labelWidthProvider = sharedLabelWidth }

        contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(section(s.sectionConnection, conn))
            add(section(s.sectionIdentity, ident))
            add(section(s.sectionCapture, capture))
            add(section(s.sectionUpload, upload, last = true))
        }

        // 這層負責「跟著 viewport 寬度走」：沒有它，JScrollPane 會用內容的
        // preferred width 當基準，窄視窗時長出水平捲軸而不是觸發單欄排列。
        val wrap = ScrollableWidthPanel().apply { add(contentPanel, BorderLayout.NORTH) }

        return JScrollPane(wrap).apply {
            border = BorderFactory.createEmptyBorder()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }
    }

    private fun buildFooter(): JPanel {
        val stats = JPanel(WrapLayout(FlowLayout.LEFT, 36, 12)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(statUploaded); add(statPending); add(statQueue); add(statSpool); add(statLast)
        }

        val btnSave = JButton(s.btnSave).apply { font = font.deriveFont(Font.BOLD) }
        val btnTest = JButton(s.btnTest)
        val btnFlush = JButton(s.btnFlush)
        val btnRefresh = JButton(s.btnRefresh)
        listOf(btnSave, btnTest, btnFlush, btnRefresh).forEach {
            it.margin = java.awt.Insets(5, 14, 5, 14)
        }

        btnSave.addActionListener { onSave() }
        btnTest.addActionListener {
            onSave()
            setStatus(Tone.IDLE) { it.statusTesting }
            Thread {
                val r = uploader.testConnection()
                SwingUtilities.invokeLater { showTestResult(r) }
            }.start()
        }
        btnFlush.addActionListener {
            setStatus(Tone.IDLE) { it.statusUploading }
            Thread {
                val result: Pair<Tone, (Strings) -> String> = try {
                    if (uploader.flushOnce()) {
                        val n = uploader.lastUploadCount
                        Tone.OK to { st: Strings -> st.sentBatch(n) }
                    } else {
                        Tone.IDLE to { st: Strings -> st.statusNothingToUpload }
                    }
                } catch (t: Throwable) {
                    val msg = t.message
                    Tone.ERR to { st: Strings -> st.uploadFailed(msg) }
                }
                SwingUtilities.invokeLater { setStatus(result.first, result.second); refreshStats() }
            }.start()
        }
        btnRefresh.addActionListener { refreshStats() }

        val buttons = JPanel(WrapLayout(FlowLayout.LEFT, 8, 8)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(btnSave); add(btnTest); add(btnFlush); add(btnRefresh)
        }

        val statusRow = JPanel(BorderLayout(7, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            // dot 要頂端對齊，狀態訊息折成多行時圓點才不會浮在中間。
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(dot) }, BorderLayout.WEST)
            add(lblStatus, BorderLayout.CENTER)
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(stats)
            add(Box.createVerticalStrut(8))
            add(buttons)
            add(Box.createVerticalStrut(10))
            add(statusRow)
        }
    }

    /**
     * 依目前寬度重算左右留白，讓標題列、表單、狀態列對齊同一條置中的欄。
     * 視窗窄的時候退回固定的 [MIN_GUTTER]，不會把欄位擠掉。
     */
    private fun applyGutters() {
        if (!::headerPanel.isInitialized) return
        val gutter = ((width - COLUMN_WIDTH) / 2).coerceAtLeast(MIN_GUTTER)
        headerPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, separatorFg),
            BorderFactory.createEmptyBorder(20, gutter, 18, gutter)
        )
        contentPanel.border = BorderFactory.createEmptyBorder(26, gutter, 28, gutter)
        footerPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, separatorFg),
            BorderFactory.createEmptyBorder(14, gutter, 16, gutter)
        )
        revalidate()
    }

    /** 區塊標題 + 分隔線 + 內容，並限制寬度上限。 */
    private fun section(heading: String, body: JComponent, last: Boolean = false): JComponent {
        val head = JLabel(heading).apply {
            font = baseFont.deriveFont(Font.BOLD, baseFont.size2D + 3f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val headBox = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(head)
            add(Box.createVerticalStrut(10))
            add(object : JSeparator() {
                override fun paintComponent(g: Graphics) {
                    foreground = separatorFg
                    background = separatorFg
                    super.paintComponent(g)
                }
            }.apply { alignmentX = Component.LEFT_ALIGNMENT })
        }

        val panel = object : JPanel(BorderLayout(0, 18)) {
            override fun getMaximumSize(): Dimension =
                Dimension(COLUMN_WIDTH, super.getPreferredSize().height)
        }
        return panel.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, if (last) 0 else 36, 0)
            add(headBox, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    private fun form() = ResponsiveForm { muted(it) }

    private fun apiKeyRow(): JComponent = JPanel(BorderLayout(8, 0)).apply {
        isOpaque = false
        add(tfApiKey, BorderLayout.CENTER)
        add(cbShowKey, BorderLayout.EAST)
    }

    private fun withUnit(field: JComponent, unit: String): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 7, 0)).apply {
            isOpaque = false
            add(field)
            add(ThemedLabel(unit, { baseFont.deriveFont(baseFont.size2D - 1f) }, { mutedFg }))
        }

    private inner class StatTile : JPanel() {
        private val value = JLabel("—").apply {
            font = baseFont.deriveFont(Font.BOLD, baseFont.size2D + 4f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        private val captionLabel = ThemedLabel("", { baseFont.deriveFont(baseFont.size2D - 1f) }, { captionFg })
            .apply { alignmentX = Component.LEFT_ALIGNMENT }

        var caption: String
            get() = captionLabel.text
            set(v) { captionLabel.text = v }

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(value)
            add(Box.createVerticalStrut(2))
            add(captionLabel)
        }

        fun set(text: String) { value.text = text }
    }

    // ================= 行為 =================

    private fun wireEditListeners() {
        val onEdit = {
            updateDerivedLabels()
            if (!dirty) {
                dirty = true
                setStatus(Tone.WARN) { it.statusUnsaved }
            }
        }
        listOf(
            tfEndpoint, tfApiKey, tfIndexPrefix, tfTester, tfProject,
            tfExcluded, tfMaxBody, tfInterval, tfBatch, tfReqTimeout
        ).forEach { it.document.addDocumentListener(onChange(onEdit)) }

        listOf(cbStoreBodies, cbWs, cbFast, cbUpload, cbPersist).forEach { it.addActionListener { onEdit() } }
    }

    private fun onChange(action: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = action()
        override fun removeUpdate(e: DocumentEvent) = action()
        override fun changedUpdate(e: DocumentEvent) = action()
    }

    private fun updateDerivedLabels() {
        val prefix = tfIndexPrefix.text.trim().ifBlank { "burp-log" }
        val project = tfProject.text.trim().ifBlank { "default" }
        lblIndexPreview.text = s.targetIndex(Config.sanitizeIndex("$prefix-$project"))

        val bytes = tfMaxBody.text.trim().toLongOrNull()
        lblBodyHint.text = if (bytes == null) s.numberRequired else s.bodyHint(bytes / 1024.0 / 1024.0)
    }

    private fun showTestResult(r: ElasticUploader.TestResult) = when (r.status) {
        ElasticUploader.ProbeStatus.OK_AUTHENTICATED -> setStatus(Tone.OK) { it.credentialsValid(r.detail) }
        ElasticUploader.ProbeStatus.OK_CLUSTER -> setStatus(Tone.OK) { it.connectionOk(r.detail) }
        ElasticUploader.ProbeStatus.UNAUTHORIZED -> setStatus(Tone.ERR) { it.statusUnauthorized }
        ElasticUploader.ProbeStatus.FORBIDDEN -> setStatus(Tone.OK) { it.statusForbidden }
        ElasticUploader.ProbeStatus.UNREACHABLE -> setStatus(Tone.ERR) { it.unreachable(r.detail) }
        ElasticUploader.ProbeStatus.OTHER -> setStatus(Tone.WARN) { it.httpOther(r.httpCode, r.detail) }
    }

    private fun onSave() {
        try {
            config.esEndpoint = tfEndpoint.text
            config.esApiKey = String(tfApiKey.password)
            config.indexPrefix = tfIndexPrefix.text
            config.testerId = tfTester.text
            config.projectId = tfProject.text
            config.excludedExtensions = tfExcluded.text.split(",")
                .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            config.maxStoredBodyBytes = tfMaxBody.text.trim().toIntOrNull() ?: config.maxStoredBodyBytes
            config.uploadIntervalSeconds = tfInterval.text.trim().toIntOrNull() ?: config.uploadIntervalSeconds
            config.uploadBatchSize = tfBatch.text.trim().toIntOrNull() ?: config.uploadBatchSize
            config.requestTimeoutSeconds = tfReqTimeout.text.trim().toIntOrNull() ?: config.requestTimeoutSeconds
            config.captureWebSockets = cbWs.isSelected
            config.storeBodies = cbStoreBodies.isSelected
            config.fastMode = cbFast.isSelected
            config.uploadEnabled = cbUpload.isSelected
            config.persistLocally = cbPersist.isSelected
            config.save()
            tfEndpoint.text = config.esEndpoint
            dirty = false
            updateDerivedLabels()
            setStatus(Tone.OK) { it.saved(config.indexName()) }
        } catch (t: Throwable) {
            val msg = t.message
            setStatus(Tone.ERR) { it.saveFailed(msg) }
        }
    }

    /**
     * 全部讀記憶體計數器，所以直接在 EDT 上跑、也不需要每次開一條執行緒。
     * 唯一碰磁碟的是落地模式下的檔案大小，那是 File.length()。
     */
    private fun refreshStats() {
        statUploaded.set(
            "%,d".format(uploader.uploadedTotal) +
                // 被 ES 永久拒絕的筆數要一直看得見，不能只靠捲過去的那行 log
                if (uploader.rejectedTotal > 0) s.rejected(uploader.rejectedTotal) else ""
        )
        statPending.set(
            "%,d".format(store.pendingCount) +
                if (store.droppedCount > 0) s.dropped(store.droppedCount) else ""
        )
        statQueue.set("%,d".format(writer.queueDepth()))
        statSpool.set(humanBytes(store.usageBytes()))
        statLast.set(
            uploader.lastUploadAt?.let {
                TIME_FMT.format(it) + if (uploader.lastUploadCount > 0) "　(+${uploader.lastUploadCount})" else ""
            } ?: "—"
        )

        // 只在錯誤「變化」時覆寫狀態列，才不會把剛剛的操作結果洗掉。
        val err = uploader.lastError
        if (err.isNotBlank() && err != lastSeenError) setStatus(Tone.ERR) { it.uploadError(err) }
        lastSeenError = err
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024 -> "%,d KB".format(bytes / 1024)
        else -> "$bytes B"
    }

    private fun startStatsTimer() {
        Timer(5000) { refreshStats() }.apply { isRepeats = true }.start()
    }

    private enum class Tone { IDLE, OK, WARN, ERR }

    /**
     * 狀態列保存的是「怎麼產生這句話」而不是產生好的字串。
     *
     * 狀態列是成員元件，不會隨版面重建而更新；若存成字串，切換語言後它會一直停在
     * 切換前那次渲染的結果。存成 (Strings) -> String 就能用新語言重新算一次。
     * 參數（index 名稱、筆數、錯誤訊息）是資料，與語言無關，由呼叫端捕捉。
     */
    private fun setStatus(tone: Tone, text: (Strings) -> String) {
        statusText = text
        statusTone = tone
        renderStatus()
    }

    private fun renderStatus() {
        val rendered = statusText(s)
        lblStatus.text = rendered
        lblStatus.toolTipText = rendered
        dot.repaint()   // 顏色在 paint 時才取，佈景換掉也會跟著變
        revalidate()
    }

    // ================= 樣式小工具 =================

    private val baseFont: Font
        get() = UIManager.getFont("Label.font") ?: Font(Font.SANS_SERIF, Font.PLAIN, 12)

    private val labelFg: Color
        get() = UIManager.getColor("Label.foreground") ?: Color(0x33, 0x33, 0x33)

    private val surfaceBg: Color
        get() = UIManager.getColor("Panel.background") ?: Color.WHITE

    /**
     * 把前景往背景方向混，而不是直接用 `Label.disabledForeground`。
     *
     * 佈景給的 disabled 色只保證「看起來不可用」，不保證比本文淡多少 —— Burp 的淺色
     * 佈景下它幾乎和標籤同深，說明文字就壓不下去。用混色才能確保層次在深淺兩種
     * 佈景下都成立。
     */
    private fun fade(ratio: Double): Color {
        val fg = labelFg
        val bg = surfaceBg
        fun mix(a: Int, b: Int) = (a * (1 - ratio) + b * ratio).toInt().coerceIn(0, 255)
        return Color(mix(fg.red, bg.red), mix(fg.green, bg.green), mix(fg.blue, bg.blue))
    }

    /** 說明文字：明顯退到背景，但仍可讀。 */
    private val mutedFg: Color get() = fade(0.45)

    /** 狀態磚的標題：比說明再深一點，因為它是在解釋旁邊那個數字。 */
    private val captionFg: Color get() = fade(0.36)

    /** 分隔線：只要能界定範圍即可，不該和文字搶注意力。 */
    private val separatorFg: Color get() = fade(0.84)

    /**
     * 標題列與狀態列的底色：由背景往文字色推一點點。
     *
     * 不寫死灰色，否則深色佈景會變成「比背景更黑」的一塊。往文字色推的方向在淺色
     * 佈景是變深、深色佈景是變淺，兩邊都會自然地浮出一層。
     */
    /** 背景夠暗就視為深色佈景；用亮度判斷，不去猜佈景名稱。 */
    private fun isDarkTheme(): Boolean {
        val bg = surfaceBg
        return (0.2126 * bg.red + 0.7152 * bg.green + 0.0722 * bg.blue) < 128
    }

    /** 語意色依佈景取：固定一組色在其中一邊一定會對比不足。 */
    private fun toneColor(tone: Tone): Color {
        val dark = isDarkTheme()
        return when (tone) {
            Tone.IDLE -> mutedFg
            Tone.OK -> if (dark) OK_DARK else OK_LIGHT
            Tone.WARN -> if (dark) WARN_DARK else WARN_LIGHT
            Tone.ERR -> if (dark) ERR_DARK else ERR_LIGHT
        }
    }

    private val chromeBg: Color
        get() {
            val bg = surfaceBg
            val fg = labelFg
            fun mix(a: Int, b: Int) = (a * 0.94 + b * 0.06).toInt().coerceIn(0, 255)
            return Color(mix(bg.red, fg.red), mix(bg.green, fg.green), mix(bg.blue, fg.blue))
        }

    private fun muted(text: String): WrapText =
        WrapText({ baseFont.deriveFont(baseFont.size2D - 1f) }, { mutedFg }).apply { this.text = text }

    private companion object {
        /** 內容欄寬度上限；超過這個寬度輸入框會拉長到難以閱讀，所以改成置中留白。 */
        const val COLUMN_WIDTH = 900
        /** 視窗窄到放不下整欄時的最小左右留白。 */
        const val MIN_GUTTER = 20

        /** 狀態磚顯示本地時間即可；完整的 ISO 時間戳在 ES 文件裡。 */
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        // 語意色分深淺兩套。單一組中間調在其中一個佈景一定會不夠對比：
        // 量過的結果，原本的綠在淺色底只有 2.85:1、原本的紅在深色底只有 2.55:1，
        // 都低於 WCAG 1.4.11 對非文字元件要求的 3:1。以下每個都 ≥ 3.8:1。
        val OK_LIGHT = Color(0x15, 0x7F, 0x4C)    // 4.50:1 on #F2F2F2
        val WARN_LIGHT = Color(0x9A, 0x64, 0x00)  // 4.47:1
        val ERR_LIGHT = Color(0xB3, 0x37, 0x2A)   // 5.36:1
        val OK_DARK = Color(0x4F, 0xC8, 0x8A)     // 5.03:1 on #3C3F41
        val WARN_DARK = Color(0xE8, 0xB0, 0x4B)   // 5.43:1
        val ERR_DARK = Color(0xF0, 0x77, 0x6A)    // 3.82:1
    }
}

// ===================== 版面工具 =====================

/**
 * 會折行的唯讀文字，用來取代說明用的 JLabel。
 *
 * JLabel 不折行，窄視窗時只會被切成 "…"；這裡用設成不可編輯、透明的 JTextArea，
 * 外觀和標籤一致但高度會隨寬度長出來。
 */
private class WrapText(
    private val fontSupplier: () -> Font,
    private val colorSupplier: () -> Color
) : JTextArea() {
    init {
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = null
        highlighter = null
    }

    // 建構時取色只在「當下」正確。使用者在 Burp 切換深淺佈景後，這些元件不會重建，
    // 顏色就會留在舊佈景；改成每次重繪時才解析。
    override fun paintComponent(g: Graphics) {
        font = fontSupplier()
        foreground = colorSupplier()
        super.paintComponent(g)
    }
}

/** 同理的 JLabel：說明文字、單位、狀態磚標題、狀態燈號都用它。 */
private class ThemedLabel(
    text: String,
    private val fontSupplier: () -> Font,
    private val colorSupplier: () -> Color
) : JLabel(text) {
    override fun paintComponent(g: Graphics) {
        font = fontSupplier()
        foreground = colorSupplier()
        super.paintComponent(g)
    }
}

/**
 * 換行後會回報正確高度的 FlowLayout。
 *
 * 原生 FlowLayout 的 preferredLayoutSize 永遠以「排成一列」計算，放進 BoxLayout 或
 * BorderLayout 時，折到第二列的元件會直接被裁掉。
 */
private class WrapLayout(align: Int, hgap: Int, vgap: Int) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, false).also { it.width -= hgap + 1 }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val targetWidth = if (target.size.width > 0) target.size.width else Int.MAX_VALUE
            val insets = target.insets
            val maxWidth = targetWidth - (insets.left + insets.right + hgap * 2)

            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            for (i in 0 until target.componentCount) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                if (rowWidth > 0 && rowWidth + hgap + d.width > maxWidth) {
                    dim.width = maxOf(dim.width, rowWidth)
                    dim.height += rowHeight + vgap
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth > 0) rowWidth += hgap
                rowWidth += d.width
                rowHeight = maxOf(rowHeight, d.height)
            }

            dim.width = maxOf(dim.width, rowWidth)
            dim.height += rowHeight
            dim.width += insets.left + insets.right + hgap * 2
            dim.height += insets.top + insets.bottom + vgap * 2
            return dim
        }
    }
}

/** 寬度跟著 viewport 走的捲動內容容器：窄視窗時改成重新排版，而不是長出水平捲軸。 */
private class ScrollableWidthPanel : JPanel(BorderLayout()), Scrollable {
    init { isOpaque = false }
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int): Int = 16
    override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int): Int = r.height
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}

private sealed class FormRow {
    class Labeled(val label: JLabel, val field: JComponent, val stretch: Boolean) : FormRow()
    /** [underCheckbox] 讓說明對齊勾選框的文字，而不是欄位欄 —— 否則會形成錯位的階梯。 */
    class Note(val comp: JComponent, val underCheckbox: Boolean) : FormRow()
    class Full(val comp: JComponent) : FormRow()
}

/**
 * 兩欄 / 單欄自動切換的表單。
 *
 * 寬度 >= [TWO_COLUMN_MIN] 且標籤欄加上最小欄位寬塞得下時用「標籤｜欄位」兩欄；
 * 否則標籤改壓在欄位正上方，欄位吃滿整個寬度。說明文字在兩欄模式下對齊欄位那一欄，
 * 單欄模式則切齊左緣。
 *
 * 用自訂 doLayout 而不是 GridBagLayout：GridBag 的欄寬在建構時就固定了，
 * 沒辦法隨容器寬度換排法。
 */
private class ResponsiveForm(
    private val hintFactory: (String) -> JComponent
) : JPanel(null as LayoutManager?) {

    private val rows = ArrayList<FormRow>()

    /**
     * 標籤欄寬度的來源。預設各自為政，但同一個面板裡的多個表單應該共用一條對齊線 ——
     * 否則每個區塊的欄位左緣都不一樣，整頁看起來就是歪的。
     */
    var labelWidthProvider: (() -> Int)? = null

    /** 這個表單自己需要的標籤欄寬。 */
    fun intrinsicLabelWidth(): Int =
        rows.filterIsInstance<FormRow.Labeled>().maxOfOrNull { it.label.preferredSize.width } ?: 0

    init { isOpaque = false }

    fun row(label: String, field: JComponent, hint: String? = null, stretch: Boolean = true) {
        val l = JLabel(label)
        add(l)
        add(field)
        rows.add(FormRow.Labeled(l, field, stretch))
        if (hint != null) note(hintFactory(hint))
    }

    /** 只佔欄位那一欄的附註，用來放說明或即時預覽。 */
    fun note(comp: JComponent) {
        add(comp)
        rows.add(FormRow.Note(comp, underCheckbox = false))
    }

    /** 勾選框下方的說明：縮排對齊勾選框的文字。 */
    fun checkboxNote(comp: JComponent) {
        add(comp)
        rows.add(FormRow.Note(comp, underCheckbox = true))
    }

    /** 橫跨整列，給 checkbox 用。 */
    fun span(comp: JComponent) {
        add(comp)
        rows.add(FormRow.Full(comp))
    }

    override fun doLayout() {
        arrange(width, apply = true)
    }

    override fun getPreferredSize(): Dimension =
        arrange(if (width > 0) width else PREFERRED_WIDTH, apply = false)

    override fun getMinimumSize(): Dimension = Dimension(MIN_FIELD, preferredSize.height)

    private fun arrange(totalWidth: Int, apply: Boolean): Dimension {
        val ins = insets
        val avail = (totalWidth - ins.left - ins.right).coerceAtLeast(MIN_FIELD)

        val labelCol = labelWidthProvider?.invoke() ?: intrinsicLabelWidth()
        val twoColumn = avail >= TWO_COLUMN_MIN && labelCol + HGAP + MIN_FIELD <= avail
        val indent = if (twoColumn) labelCol + HGAP else 0
        val fieldW = avail - indent

        var y = ins.top
        var widest = 0

        for ((i, r) in rows.withIndex()) {
            when (r) {
                is FormRow.Labeled -> {
                    val fp = r.field.preferredSize
                    val lp = r.label.preferredSize
                    val w = if (r.stretch) fieldW else minOf(fp.width, fieldW)
                    if (twoColumn) {
                        val h = maxOf(lp.height, fp.height)
                        if (apply) {
                            // 標籤靠右貼齊欄位欄：左緣參差不齊是「PoC 感」的主要來源。
                            r.label.horizontalAlignment = SwingConstants.RIGHT
                            r.label.setBounds(ins.left, y + (h - lp.height) / 2, labelCol, lp.height)
                            r.field.setBounds(ins.left + indent, y + (h - fp.height) / 2, w, fp.height)
                        }
                        y += h + if (nextIsNote(i)) HINT_GAP else ROW_GAP
                    } else {
                        if (apply) {
                            r.label.horizontalAlignment = SwingConstants.LEFT
                            r.label.setBounds(ins.left, y, minOf(lp.width, avail), lp.height)
                            r.field.setBounds(ins.left, y + lp.height + 3, w, fp.height)
                        }
                        y += lp.height + 3 + fp.height + if (nextIsNote(i)) HINT_GAP else ROW_GAP
                    }
                    widest = maxOf(widest, indent + w)
                }

                is FormRow.Note -> {
                    val x = if (r.underCheckbox) CHECKBOX_INDENT else indent
                    val w = avail - x
                    val h = heightFor(r.comp, w)
                    if (apply) r.comp.setBounds(ins.left + x, y, w, h)
                    y += h + NOTE_GAP
                    widest = maxOf(widest, x + w)
                }

                is FormRow.Full -> {
                    val p = r.comp.preferredSize
                    val w = minOf(p.width, avail)
                    if (apply) r.comp.setBounds(ins.left, y, w, p.height)
                    y += p.height + if (nextIsNote(i)) HINT_GAP else ROW_GAP
                    widest = maxOf(widest, w)
                }
            }
        }

        val height = (y - ROW_GAP).coerceAtLeast(ins.top) + ins.bottom
        return Dimension(ins.left + ins.right + widest, height)
    }

    private fun nextIsNote(i: Int): Boolean = rows.getOrNull(i + 1) is FormRow.Note

    /** 折行元件（[WrapText]）的高度取決於寬度，先給寬度才量得到。 */
    private fun heightFor(comp: JComponent, w: Int): Int {
        comp.setSize(w, Short.MAX_VALUE.toInt())
        return comp.preferredSize.height
    }

    private companion object {
        /** 低於這個可用寬度就改單欄：標籤壓在欄位上方。 */
        const val TWO_COLUMN_MIN = 520
        const val MIN_FIELD = 200
        const val PREFERRED_WIDTH = 820
        const val HGAP = 18
        const val ROW_GAP = 12
        /** 說明緊貼它說明的那個欄位，下一列才拉開 —— 製造「一組一組」的節奏。 */
        const val NOTE_GAP = 14
        /** 欄位到它自己的說明之間，刻意比列距小。 */
        const val HINT_GAP = 5
        /** 勾選框文字的左緣，說明要對齊這裡。 */
        val CHECKBOX_INDENT = (UIManager.getIcon("CheckBox.icon")?.iconWidth ?: 16) + 6
    }
}
