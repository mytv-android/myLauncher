package com.github.mytv.core.install

/**
 * Minimal JSON reader for `.xapk` manifests. The module must not depend on a third
 * party JSON library, and `org.json` is only a stub in local unit tests, so this small
 * parser keeps the selection logic testable on the JVM.
 */
internal object MiniJson {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.readValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "trailing characters" }
        return value
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(index < text.length) { "unexpected end of input" }
            return when (val c = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> {
                    expect("true")
                    true
                }
                'f' -> {
                    expect("false")
                    false
                }
                'n' -> {
                    expect("null")
                    null
                }
                else -> {
                    require(c == '-' || c.isDigit()) { "unexpected character '$c'" }
                    readNumber()
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            index++
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                require(peek() == '"') { "object key must be a string" }
                val key = readString()
                skipWhitespace()
                require(peek() == ':') { "expected ':'" }
                index++
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            index++
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                result += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            require(text[index] == '"')
            index++
            val out = StringBuilder()
            while (true) {
                require(index < text.length) { "unterminated string" }
                when (val c = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(index < text.length) { "unterminated escape" }
                        when (val escape = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(index + 4 <= text.length) { "bad unicode escape" }
                                val code = text.substring(index, index + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("bad unicode escape")
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("bad escape")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): Number {
            val start = index
            if (text[index] == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            var isDouble = false
            if (index < text.length && text[index] == '.') {
                isDouble = true
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                isDouble = true
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val token = text.substring(start, index)
            return if (isDouble) token.toDouble() else token.toLongOrNull() ?: token.toDouble()
        }

        private fun expect(token: String) {
            require(text.startsWith(token, index)) { "expected $token" }
            index += token.length
        }

        private fun peek(): Char {
            require(index < text.length) { "unexpected end of input" }
            return text[index]
        }
    }
}
