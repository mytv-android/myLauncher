package com.github.mytv.data.restricted

/**
 * Standard Base64 with padding. Implemented locally because java.util.Base64 requires
 * API 26 while the launcher supports API 23.
 */
internal object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val REVERSE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index + 2 < bytes.size) {
            val value = ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                (bytes[index + 2].toInt() and 0xFF)
            out.append(ALPHABET[(value ushr 18) and 63])
                .append(ALPHABET[(value ushr 12) and 63])
                .append(ALPHABET[(value ushr 6) and 63])
                .append(ALPHABET[value and 63])
            index += 3
        }
        when (bytes.size - index) {
            1 -> {
                val value = (bytes[index].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(value ushr 18) and 63])
                    .append(ALPHABET[(value ushr 12) and 63])
                    .append("==")
            }
            2 -> {
                val value = ((bytes[index].toInt() and 0xFF) shl 16) or
                    ((bytes[index + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(value ushr 18) and 63])
                    .append(ALPHABET[(value ushr 12) and 63])
                    .append(ALPHABET[(value ushr 6) and 63])
                    .append('=')
            }
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray? {
        val clean = text.filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return ByteArray(0)
        if (clean.length % 4 != 0) return null
        val out = java.io.ByteArrayOutputStream(clean.length / 4 * 3)
        var index = 0
        while (index < clean.length) {
            val c0 = value(clean[index])
            val c1 = value(clean[index + 1])
            val c2 = value(clean[index + 2])
            val c3 = value(clean[index + 3])
            if (c0 < 0 || c1 < 0) return null
            val pad2 = clean[index + 2] == '='
            val pad3 = clean[index + 3] == '='
            when {
                pad2 && pad3 -> out.write((c0 shl 2) or (c1 ushr 4))
                !pad2 && pad3 -> {
                    if (c2 < 0) return null
                    out.write((c0 shl 2) or (c1 ushr 4))
                    out.write(((c1 and 0x0F) shl 4) or (c2 ushr 2))
                }
                pad2 -> return null
                else -> {
                    if (c2 < 0 || c3 < 0) return null
                    out.write((c0 shl 2) or (c1 ushr 4))
                    out.write(((c1 and 0x0F) shl 4) or (c2 ushr 2))
                    out.write(((c2 and 0x03) shl 6) or c3)
                }
            }
            index += 4
        }
        return out.toByteArray()
    }

    private fun value(char: Char): Int =
        if (char.code < REVERSE.size) REVERSE[char.code] else -1
}
