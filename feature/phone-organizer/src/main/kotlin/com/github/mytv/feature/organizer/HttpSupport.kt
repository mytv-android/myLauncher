package com.github.mytv.feature.organizer

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal object HttpSupport {
    const val JSON_MIME = "application/json; charset=utf-8"
    const val TEXT_MIME = "text/plain; charset=utf-8"
    const val HTML_MIME = "text/html; charset=utf-8"
    const val JS_MIME = "application/javascript; charset=utf-8"
    const val CSS_MIME = "text/css; charset=utf-8"
    const val SVG_MIME = "image/svg+xml"
    const val MAX_JSON_BYTES = 64 * 1024

    class HttpError(
        val code: Int,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
    ) : Exception(body)

    private val reasons = mapOf(
        200 to "OK",
        204 to "No Content",
        206 to "Partial Content",
        304 to "Not Modified",
        400 to "Bad Request",
        401 to "Unauthorized",
        403 to "Forbidden",
        404 to "Not Found",
        405 to "Method Not Allowed",
        409 to "Conflict",
        413 to "Payload Too Large",
        415 to "Unsupported Media Type",
        416 to "Range Not Satisfiable",
        421 to "Misdirected Request",
        500 to "Internal Server Error",
    )

    // NanoHTTPD 把 description 原样写进状态行，所以这里必须带状态码
    fun status(code: Int): NanoHTTPD.Response.IStatus =
        object : NanoHTTPD.Response.IStatus {
            override fun getRequestStatus(): Int = code
            override fun getDescription(): String = "$code ${reasons[code] ?: "Status"}"
        }

    fun fixed(
        code: Int,
        mime: String,
        bytes: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): NanoHTTPD.Response {
        val response = NanoHTTPD.newFixedLengthResponse(
            status(code),
            mime,
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
        for ((name, value) in headers) response.addHeader(name, value)
        return response
    }

    fun fixed(
        code: Int,
        mime: String,
        text: String,
        headers: Map<String, String> = emptyMap(),
    ): NanoHTTPD.Response = fixed(code, mime, text.toByteArray(StandardCharsets.UTF_8), headers)

    fun json(code: Int, value: Any, headers: Map<String, String> = emptyMap()): NanoHTTPD.Response {
        val text = when (value) {
            is JSONObject -> value.toString()
            is JSONArray -> value.toString()
            else -> value.toString()
        }
        return fixed(code, JSON_MIME, text, headers)
    }

    fun error(code: Int, message: String, headers: Map<String, String> = emptyMap()): NanoHTTPD.Response {
        val json = JSONObject().put("error", message)
        return json(code, json, headers)
    }

    fun stream(
        code: Int,
        mime: String,
        input: InputStream,
        length: Long,
        headers: Map<String, String> = emptyMap(),
    ): NanoHTTPD.Response {
        val response = NanoHTTPD.newFixedLengthResponse(status(code), mime, input, length)
        for ((name, value) in headers) response.addHeader(name, value)
        return response
    }

    fun chunked(
        code: Int,
        mime: String,
        input: InputStream,
        headers: Map<String, String> = emptyMap(),
    ): NanoHTTPD.Response {
        val response = NanoHTTPD.newChunkedResponse(status(code), mime, input)
        for ((name, value) in headers) response.addHeader(name, value)
        return response
    }

    fun headers(session: NanoHTTPD.IHTTPSession): Map<String, String> = session.headers

    fun header(session: NanoHTTPD.IHTTPSession, name: String): String? =
        session.headers[name.lowercase()]

    fun contentLength(session: NanoHTTPD.IHTTPSession): Long? =
        header(session, "content-length")?.trim()?.toLongOrNull()

    fun readJson(session: NanoHTTPD.IHTTPSession): JSONObject {
        val text = readText(session, MAX_JSON_BYTES)
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            throw HttpError(400, "bad json")
        }
    }

    fun readText(session: NanoHTTPD.IHTTPSession, maxBytes: Int): String {
        val declared = contentLength(session)
        if (declared != null && declared > maxBytes) throw HttpError(413, "body too large")
        val limit = (declared ?: maxBytes.toLong()).coerceAtMost(maxBytes.toLong()).toInt()
        val buffer = ByteArray(limit.coerceAtLeast(1))
        var offset = 0
        val input = session.inputStream
        while (offset < limit) {
            val read = input.read(buffer, offset, limit - offset)
            if (read < 0) break
            offset += read
        }
        if (declared == null) {
            val extra = input.read()
            if (extra >= 0) throw HttpError(413, "body too large")
        }
        return String(buffer, 0, offset, StandardCharsets.UTF_8)
    }

    fun queryParameter(session: NanoHTTPD.IHTTPSession, name: String): String? =
        session.parameters[name]?.firstOrNull()

    fun jsonText(value: Any): JSONObject = when (value) {
        is JSONObject -> value
        else -> JSONObject()
    }
}
