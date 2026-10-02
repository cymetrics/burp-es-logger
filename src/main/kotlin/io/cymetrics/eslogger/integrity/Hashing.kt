package io.cymetrics.eslogger.integrity

import java.security.MessageDigest

object Hashing {

    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256Hex(data: ByteArray): String = sha256Hex(data, 0, data.size)

    /** 可指定範圍，避免為了算 hash 再複製一份 body。 */
    fun sha256Hex(data: ByteArray, offset: Int, length: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(data, offset, length)
        return toHex(md.digest())
    }

    fun sha256Hex(data: String): String = sha256Hex(data.toByteArray(Charsets.UTF_8))

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    val GENESIS: String = "0".repeat(64)

    /**
     * 把一筆記錄的關鍵欄位組成 canonical 字串，再和前一筆 hash 串成鏈。
     * 用 0x1f (Unit Separator) 當分隔字元，避免欄位內容撞到分隔符。
     * record_sha256 = SHA256( material || US || prevHash )
     */
    fun recordHash(fields: List<String>, prevHash: String): String {
        val sep = '\u001f'
        val material = fields.joinToString(sep.toString()) + sep + prevHash
        return sha256Hex(material)
    }
}
