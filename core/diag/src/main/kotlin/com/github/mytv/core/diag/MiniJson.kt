package com.github.mytv.core.diag

/**
 * Minimal JSON reader for the bundled device profile asset and ADB override payloads.
 *
 * Keeping the parser local avoids pulling org.json into JVM unit tests, where the Android
 * stub would throw.
 */
internal object MiniJson {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "Unexpected trailing content" }
        return value
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            require(index < text.length) { "Unexpected end of JSON" }
            return when (val char = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> if (char == '-' || char.isDigit()) {
                    parseNumber()
                } else {
                    throw IllegalArgumentException("Unexpected character $char")
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            index++
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (index < text.length && text[index] == '}') {
                index++
                return out
            }
            while (true) {
                skipWhitespace()
                require(index < text.length && text[index] == '"') { "Expected object key" }
                val key = parseString()
                skipWhitespace()
                require(index < text.length && text[index] == ':') { "Expected ':'" }
                index++
                out[key] = parseValue()
                skipWhitespace()
                require(index < text.length) { "Unterminated object" }
                when (text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return out
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            index++
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (index < text.length && text[index] == ']') {
                index++
                return out
            }
            while (true) {
                out += parseValue()
                skipWhitespace()
                require(index < text.length) { "Unterminated array" }
                when (text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return out
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            require(index < text.length && text[index] == '"') { "Expected string" }
            index++
            val out = StringBuilder()
            while (index < text.length) {
                when (val char = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(index < text.length) { "Unterminated escape" }
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(index + 4 <= text.length) { "Invalid unicode escape" }
                                val hex = text.substring(index, index + 4)
                                index += 4
                                out.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("Invalid escape $escaped")
                        }
                    }
                    else -> out.append(char)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): Any {
            val start = index
            if (text[index] == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            var decimal = false
            if (index < text.length && text[index] == '.') {
                decimal = true
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                decimal = true
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            return if (decimal) raw.toDouble() else raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, index)) { "Invalid literal" }
            index += literal.length
            return value
        }
    }
}
