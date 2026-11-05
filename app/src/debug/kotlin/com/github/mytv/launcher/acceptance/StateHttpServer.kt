package com.github.mytv.launcher.acceptance

import android.content.Context
import com.github.mytv.launcher.StateHub
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** 验收状态接口的本地传输：与 ContentProvider 返回同一份 JSON，只在 debug 构建里存在。 */
object StateHttpServer {
    private const val PORT = 8765

    @Volatile
    private var started = false

    fun start(appContext: Context?) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            Thread({
                runCatching {
                    ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1")).use { server ->
                        while (true) {
                            val socket = server.accept()
                            Thread { serve(socket, appContext) }.apply { isDaemon = true }.start()
                        }
                    }
                }
            }, "ml-state-http").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket, context: Context?) {
        runCatching {
            socket.use { s ->
                s.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII))
                val request = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val target = request.split(' ').getOrNull(1) ?: return
                val uri = URI("http://127.0.0.1$target")
                val result = when (uri.path) {
                    "/state" -> Resp(StateHub.json(), "200 OK", "application/json; charset=utf-8")
                    "/call" -> {
                        val params = (uri.rawQuery ?: "").split('&').mapNotNull { pair ->
                            val i = pair.indexOf('=')
                            if (i <= 0) {
                                null
                            } else {
                                URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                                    URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                            }
                        }.toMap()
                        val method = params["method"] ?: ""
                        Resp(StateProvider.handleCall(context, method, params["arg"]))
                    }
                    else -> Resp("not found", "404 Not Found")
                }
                val body = result.body.toByteArray(StandardCharsets.UTF_8)
                val head = "HTTP/1.1 ${result.status}\r\nContent-Type: ${result.type}\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                s.getOutputStream().write(head.toByteArray(StandardCharsets.US_ASCII))
                s.getOutputStream().write(body)
                s.getOutputStream().flush()
            }
        }
    }

    private data class Resp(
        val body: String,
        val status: String = "200 OK",
        val type: String = "text/plain; charset=utf-8",
    )
}
