package top.zzcoding.codeman

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 一台机器的 SSE 长连接：手写 `event:`/`data:` 解析，指数退避重连。
 * 不依赖 OkHttp，只用 HttpURLConnection。
 */
class SseClient(
    val machine: Machine,
    private val onEvent: (event: String, data: String) -> Unit,
    private val onState: (state: State) -> Unit,
) {
    enum class State { CONNECTING, CONNECTED, RECONNECTING, STOPPED }

    @Volatile var state: State = State.CONNECTING
        private set

    /** 最近一次错误，供设置页展示。 */
    @Volatile var lastError: String? = null
        private set

    @Volatile private var stopped = false
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var backoffMs = INITIAL_BACKOFF_MS
    private val lock = Object()
    private var thread: Thread? = null

    companion object {
        private const val INITIAL_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 60_000L
        /** 服务端每 15s 一次 sse:heartbeat，超过这个时间没有任何字节视为死连接。 */
        private const val READ_TIMEOUT_MS = 120_000
        private const val CONNECT_TIMEOUT_MS = 15_000

        /** 仅用于用户自己配置的自签 HTTPS 机器：跳过证书校验。 */
        private val trustAll: SSLContext by lazy {
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }), SecureRandom())
            }
        }
    }

    fun start() {
        if (thread != null) return
        thread = Thread({ loop() }, "sse-${machine.name}").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        setState(State.STOPPED)
        closeConnection()
        synchronized(lock) { lock.notifyAll() }
        thread?.interrupt()
    }

    /** 网络变化：断开当前连接、清零退避，立刻重连。 */
    fun wake() {
        if (stopped) return
        backoffMs = INITIAL_BACKOFF_MS
        closeConnection()
        synchronized(lock) { lock.notifyAll() }
    }

    private fun closeConnection() {
        val c = conn ?: return
        conn = null
        try { c.disconnect() } catch (_: Exception) {}
    }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        try { onState(s) } catch (_: Exception) {}
    }

    private fun loop() {
        while (!stopped) {
            try {
                connectAndRead()
                // 正常 EOF（服务端重启等）：立刻重连一次，再退避
                if (stopped) break
                lastError = "连接被关闭"
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (stopped) break
                lastError = e.message ?: e.javaClass.simpleName
            }
            setState(State.RECONNECTING)
            val wait = backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            val interrupted = synchronized(lock) {
                try { lock.wait(wait); false } catch (_: InterruptedException) { true }
            }
            if (interrupted) break
        }
        setState(State.STOPPED)
    }

    private fun connectAndRead() {
        setState(if (backoffMs == INITIAL_BACKOFF_MS) State.CONNECTING else State.RECONNECTING)
        val url = URL(machine.baseUrl + "api/events")
        val c = url.openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) {
            c.sslSocketFactory = trustAll.socketFactory
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        c.useCaches = false
        c.setRequestProperty("Accept", "text/event-stream")
        c.setRequestProperty("Cache-Control", "no-cache")
        c.setRequestProperty("Accept-Encoding", "identity")
        val cred = Base64.encodeToString(
            "${machine.username}:${machine.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        c.setRequestProperty("Authorization", "Basic $cred")
        conn = c
        c.connect()
        val code = c.responseCode
        if (code != 200) {
            throw IllegalStateException("HTTP $code")
        }
        val reader = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8), 16 * 1024)
        setState(State.CONNECTED)
        backoffMs = INITIAL_BACKOFF_MS
        lastError = null

        var event = ""
        val data = StringBuilder()
        while (!stopped) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty() || event.isNotEmpty()) {
                        val ev = if (event.isEmpty()) "message" else event
                        val payload = data.toString()
                        event = ""
                        data.setLength(0)
                        try { onEvent(ev, payload) } catch (_: Exception) {}
                    }
                }
                line.startsWith(":") -> { /* 注释/填充 */ }
                line.startsWith("event:") -> event = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
                else -> { /* id:/retry: 忽略 */ }
            }
        }
        closeConnection()
    }
}
