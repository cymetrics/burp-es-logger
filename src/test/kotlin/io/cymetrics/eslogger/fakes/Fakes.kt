// Montoya 的 Preferences 介面裡 byte / short 那組存取器已標記棄用，但實作介面仍得覆寫它們。
@file:Suppress("OVERRIDE_DEPRECATION")

package io.cymetrics.eslogger.fakes

import burp.api.montoya.logging.Logging
import io.cymetrics.eslogger.config.Config
import burp.api.montoya.persistence.Preferences
import io.cymetrics.eslogger.integrity.Hashing
import io.cymetrics.eslogger.storage.NewRecord
import io.cymetrics.eslogger.storage.Pending
import io.cymetrics.eslogger.storage.RecordSpool
import io.cymetrics.eslogger.storage.SpoolKind
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Burp 偏好設定的記憶體版本。設定與鏈尾都走這裡，所以測試可以模擬「重載」。
 *
 * byte / short 那組存取器在 Montoya 已標記棄用，但介面仍要求實作，所以單獨抑制警告 ——
 * 留著噪音的話，哪天開啟 allWarningsAsErrors 就會從測試開始壞。
 */
class FakePreferences : Preferences {
    private val strings = HashMap<String, String>()
    private val booleans = HashMap<String, Boolean>()
    private val integers = HashMap<String, Int>()
    private val longs = HashMap<String, Long>()
    private val bytes = HashMap<String, Byte>()
    private val shorts = HashMap<String, Short>()

    override fun getString(key: String): String? = strings[key]
    override fun setString(key: String, value: String) { strings[key] = value }
    override fun deleteString(key: String) { strings.remove(key) }
    override fun stringKeys(): MutableSet<String> = strings.keys

    override fun getBoolean(key: String): Boolean? = booleans[key]
    override fun setBoolean(key: String, value: Boolean) { booleans[key] = value }
    override fun deleteBoolean(key: String) { booleans.remove(key) }
    override fun booleanKeys(): MutableSet<String> = booleans.keys

    override fun getByte(key: String): Byte? = bytes[key]
    override fun setByte(key: String, value: Byte) { bytes[key] = value }
    override fun deleteByte(key: String) { bytes.remove(key) }
    override fun byteKeys(): MutableSet<String> = bytes.keys

    override fun getShort(key: String): Short? = shorts[key]
    override fun setShort(key: String, value: Short) { shorts[key] = value }
    override fun deleteShort(key: String) { shorts.remove(key) }
    override fun shortKeys(): MutableSet<String> = shorts.keys

    override fun getInteger(key: String): Int? = integers[key]
    override fun setInteger(key: String, value: Int) { integers[key] = value }
    override fun deleteInteger(key: String) { integers.remove(key) }
    override fun integerKeys(): MutableSet<String> = integers.keys

    override fun getLong(key: String): Long? = longs[key]
    override fun setLong(key: String, value: Long) { longs[key] = value }
    override fun deleteLong(key: String) { longs.remove(key) }
    override fun longKeys(): MutableSet<String> = longs.keys
}

/** 收集訊息而不是印出來，測試才能斷言「出事時有沒有講」。 */
class FakeLogging : Logging {
    val output = ArrayList<String>()
    val errors = ArrayList<String>()
    val events = ArrayList<String>()

    override fun output(): PrintStream = PrintStream(ByteArrayOutputStream())
    override fun error(): PrintStream = PrintStream(ByteArrayOutputStream())
    override fun logToOutput(message: String) { output += message }
    override fun logToError(message: String) { errors += message }
    override fun logToError(message: String, throwable: Throwable) { errors += message }
    override fun logToError(throwable: Throwable) { errors += throwable.toString() }
    override fun raiseDebugEvent(message: String) { events += "DEBUG $message" }
    override fun raiseInfoEvent(message: String) { events += "INFO $message" }
    override fun raiseErrorEvent(message: String) { events += "ERROR $message" }
    override fun raiseCriticalEvent(message: String) { events += "CRITICAL $message" }
}

/** 記下所有寫入的 outbox，不丟棄任何東西，讓測試能檢查整條鏈。 */
class RecordingSpool : RecordSpool {
    val written = ArrayList<NewRecord>()

    override var lastSeq: Long = 0
        private set
    override var lastHash: String = Hashing.GENESIS
        private set
    override val pendingCount: Long get() = written.size.toLong()
    override val droppedCount: Long get() = 0
    override val kind: SpoolKind get() = SpoolKind.MEMORY

    override fun usageBytes(): Long = written.sumOf { it.byteSize.toLong() }

    override fun insertAll(records: List<NewRecord>) {
        if (records.isEmpty()) return
        written += records
        lastSeq = records.last().seq
        lastHash = records.last().recordHash
    }

    override fun pendingBatch(limit: Int): List<Pending> =
        written.take(limit).map { Pending(it.seq, it.docId, it.docJson, it.byteSize) }

    override fun purge(seqs: Collection<Long>): Int {
        val before = written.size
        written.removeAll { it.seq in seqs }
        return before - written.size
    }

    override fun close() {}
}

/**
 * 測試共用的設定。三個測試類別原本各自組一份，而且其中一份忘了設 persistLocally，
 * 預設值一變它就會悄悄改測另一條分支。
 */
fun testConfig(
    prefs: FakePreferences = FakePreferences(),
    tester: String = "zet",
    project: String = "acme",
    persistLocally: Boolean = false
): Config = Config(prefs).apply {
    this.testerId = tester
    this.projectId = project
    this.persistLocally = persistLocally
    save()
}
