package io.cymetrics.eslogger.integrity

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.math.BigDecimal
import java.security.MessageDigest

/** Versioned, portable digest of the exact stored JSON, including stored headers and bodies. */
object IntegrityV2 {
    const val SCHEME = "burp-es-logger-v2"

    fun attach(doc: JsonObject, prevHash: String): String {
        // Gson leaves lone UTF-16 surrogates literal; UTF-8 transport would replace them
        // after hashing. Make the stored value explicit before computing its digest.
        normalizeStrings(doc)
        val payloadHash = payloadHash(doc)
        val recordHash = Hashing.recordHash(listOf(SCHEME, payloadHash), prevHash)
        doc.add("integrity", JsonObject().apply {
            addProperty("algo", "sha256")
            addProperty("scheme", SCHEME)
            addProperty("payload_sha256", payloadHash)
            addProperty("prev_hash", prevHash)
            addProperty("record_sha256", recordHash)
        })
        return recordHash
    }

    private fun normalizeStrings(value: JsonElement): JsonElement {
        if (value.isJsonObject) {
            val fields = value.asJsonObject.entrySet().map { (key, child) ->
                unicodeScalarString(key) to normalizeStrings(child)
            }
            require(fields.map { it.first }.distinct().size == fields.size) {
                "JSON keys collide after invalid Unicode replacement"
            }
            value.asJsonObject.keySet().toList().forEach { value.asJsonObject.remove(it) }
            fields.forEach { (key, child) -> value.asJsonObject.add(key, child) }
        } else if (value.isJsonArray) {
            for (i in 0 until value.asJsonArray.size()) {
                value.asJsonArray.set(i, normalizeStrings(value.asJsonArray[i]))
            }
        } else if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            val original = value.asString
            val normalized = unicodeScalarString(original)
            if (normalized !== original) return JsonPrimitive(normalized)
        }
        return value
    }

    private fun unicodeScalarString(value: String): String {
        var replacement: StringBuilder? = null
        var i = 0
        while (i < value.length) {
            val char = value[i]
            if (Character.isHighSurrogate(char) && i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) {
                replacement?.append(char)?.append(value[i + 1])
                i += 2
                continue
            }
            if (Character.isSurrogate(char)) {
                if (replacement == null) replacement = StringBuilder(value.length).append(value, 0, i)
                replacement.append('\uFFFD')
            } else {
                replacement?.append(char)
            }
            i++
        }
        return replacement?.toString() ?: value
    }

    /**
     * The encoding is specified in docs/integrity.md and independently implemented by the
     * Python verifier. Hash incrementally: a second serialized copy of the body is unnecessary.
     */
    fun payloadHash(doc: JsonObject): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val stringBuffer = ByteArray(8192)

        fun ascii(value: String) = digest.update(value.toByteArray(Charsets.US_ASCII))

        fun string(value: String) {
            ascii("s${value.length.toLong() * 2}:")
            // Encode Java's UTF-16 code units directly, including an unpaired surrogate.
            // Charset encoders can replace those; Python uses utf-16-be + surrogatepass.
            var used = 0
            for (char in value) {
                stringBuffer[used++] = (char.code ushr 8).toByte()
                stringBuffer[used++] = char.code.toByte()
                if (used == stringBuffer.size) {
                    digest.update(stringBuffer)
                    used = 0
                }
            }
            if (used != 0) digest.update(stringBuffer, 0, used)
        }

        fun encode(value: JsonElement, root: Boolean = false) {
            when {
                value.isJsonNull -> ascii("n")
                value.isJsonObject -> {
                    val fields = value.asJsonObject.entrySet()
                        .filter { !root || it.key != "integrity" }
                        .sortedBy { it.key } // Java string order is unsigned UTF-16 code-unit order.
                    ascii("o${fields.size}:")
                    for ((key, child) in fields) {
                        string(key)
                        encode(child)
                    }
                }
                value.isJsonArray -> {
                    ascii("a${value.asJsonArray.size()}:")
                    for (child in value.asJsonArray) encode(child)
                }
                value.asJsonPrimitive.isBoolean -> ascii(if (value.asBoolean) "t" else "f")
                value.asJsonPrimitive.isNumber -> {
                    val number = BigDecimal(value.asString).stripTrailingZeros().toPlainString()
                    ascii("d${number.length}:$number")
                }
                else -> string(value.asString)
            }
        }

        encode(doc, root = true)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
