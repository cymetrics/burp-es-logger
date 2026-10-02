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
     *
     *     record_sha256 = SHA256( prev_hash ‖ for each field: len(utf8 bytes) ‖ ":" ‖ field )
     *
     * 每個欄位前置它自己的長度，而不是用分隔字元串起來。分隔字元的做法有歧義：
     * 欄位內容只要含有那個字元，就能偽造欄位邊界，讓兩筆不同的紀錄算出同一個雜湊
     * （["a","b"] 與 ["a\u001fb"] 會相同）。URL 是受測方與測試者都能影響的欄位，
     * 這種歧義在稽核用途下不能留。長度前綴則讓每一種欄位組合只有唯一一種表示法。
     *
     * prev_hash 放在最前面：它固定是 64 個十六進位字元，不會和後面的長度前綴混淆。
     */
    fun recordHash(fields: List<String>, prevHash: String): String {
        val material = StringBuilder(prevHash)
        for (field in fields) {
            material.append(field.toByteArray(Charsets.UTF_8).size).append(':').append(field)
        }
        return sha256Hex(material.toString())
    }
}
