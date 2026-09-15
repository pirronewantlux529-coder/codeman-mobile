package top.zzcoding.codeman

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.VpnService
import android.net.http.SslError
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 主窗口。[WindowActivity] 继承它作为可多开的侧边窗口：折叠屏展开或分屏时，
 * 网页里「在新窗口打开」的会话/文件预览经 CodemanHost 桥开到相邻一侧。
 */
open class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bubble: ImageButton
    @Volatile protected var current: Machine? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    /** 通知点击带来的会话 id，页面加载完成后注入 app.selectSession */
    private var pendingSessionId: String? = null

    companion object {
        private const val REQ_VPN = 100
        private val LINE_HEIGHTS = floatArrayOf(1.0f, 1.15f, 1.3f, 1.5f)
    }

    /** HTTP 环境下网页拿不到 navigator.clipboard，桥接到原生剪贴板 */
    inner class ClipboardBridge {
        @JavascriptInterface
        fun write(text: String) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("codeman", text))
        }

        @JavascriptInterface
        fun read(): String {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        }
    }

    /** 侧边窗口（可多开、固定机器、不处理通知/VPN 自动连接） */
    protected open val isSideWindow = false

    /** 本窗口显示的机器：主窗口跟随全局选择 */
    protected open fun machineForWindow(): Machine? = MachineStore.selected(this)

    /** 首次加载的地址 */
    protected open fun startUrl(m: Machine): String = m.baseUrl

    protected fun loadInWindow(url: String) = webView.loadUrl(url)

    /** 菜单里切换机器 */
    protected open fun chooseMachine(m: Machine) {
        MachineStore.setSelected(this, m.id)
        current = m
        webView.loadUrl(m.baseUrl)
    }

    /**
     * 暴露给 Codeman 网页的宿主能力（上游 hasHostWindows/openInHostWindow）：
     * 同源页面在相邻窗口打开，其它地址交给系统浏览器。
     */
    inner class HostBridge {
        @JavascriptInterface
        fun openWindow(url: String): Boolean {
            val m = current ?: return false
            val uri = Uri.parse(url)
            if (uri.scheme != "http" && uri.scheme != "https") return false
            runOnUiThread {
                try {
                    if (isSameOrigin(uri, m)) openSideWindow(url, m)
                    else startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, getString(R.string.window_open_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
            return true
        }

        @JavascriptInterface
        fun closeWindow() {
            runOnUiThread { if (isSideWindow) finishAndRemoveTask() }
        }
    }

    private fun isSameOrigin(uri: Uri, m: Machine): Boolean {
        val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
        return uri.host.equals(m.host, ignoreCase = true) && port == m.port &&
            (uri.scheme == "https") == m.useHttps
    }

    /** 新开一个侧边窗口；分屏/折叠屏大屏上 LAUNCH_ADJACENT 会放到另一半 */
    protected fun openSideWindow(url: String, m: Machine) {
        val i = Intent(this, WindowActivity::class.java)
            .putExtra(WindowActivity.EXTRA_URL, url)
            .putExtra(WindowActivity.EXTRA_MACHINE_ID, m.id)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            )
        startActivity(i)
    }

    /**
     * 当前会话开到相邻窗口。新版 Codeman 走 app.detachSession（主窗口会把该标签标成
     * detached、不再抢终端尺寸）；老版本没有宿主接口，直接打开 /session/<id>。
     */
    private fun openActiveSessionWindow() {
        val m = current ?: return
        webView.evaluateJavascript(
            """
            (function(){
              try {
                var id = window.app && app.activeSessionId;
                if (!id) return '';
                if (typeof app.hasHostWindows === 'function' && app.hasHostWindows()) { app.detachSession(id); return 'host'; }
                return 'id:' + id;
              } catch(e) { return ''; }
            })();
            """.trimIndent()
        ) { result ->
            val r = try { org.json.JSONTokener(result).nextValue() as? String ?: "" } catch (_: Exception) { "" }
            when {
                r == "host" -> {}
                r.startsWith("id:") -> openSideWindow(m.baseUrl + "session/" + Uri.encode(r.removePrefix("id:")), m)
                else -> Toast.makeText(this, R.string.no_active_session, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun prefs() = getSharedPreferences("ui", Context.MODE_PRIVATE)

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        bubble = findViewById(R.id.btn_bubble)
        setupBubble()

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.addJavascriptInterface(ClipboardBridge(), "AndroidClipboard")
        webView.addJavascriptInterface(HostBridge(), "CodemanHost")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // 仅放行已配置机器的自签证书
                val trusted = MachineStore.load(this@MainActivity).any { m ->
                    error.url.contains("://${m.host}:")
                }
                if (trusted) handler.proceed() else handler.cancel()
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView, handler: HttpAuthHandler, host: String, realm: String
            ) {
                val m = current
                if (m != null && m.host == host) handler.proceed(m.username, m.password)
                else handler.cancel()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                findViewById<View>(R.id.progress).visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                findViewById<View>(R.id.progress).visibility = View.GONE
                injectClipboardPolyfill()
                applyLineHeight(prefs().getFloat("lineHeight", 1.0f))
                injectSelectSession()
            }
        }

        WgManager.onStateChanged = { st ->
            runOnUiThread { updateBubbleTint(st) }
        }

        if (isSideWindow) return
        handleNotificationIntent(intent)
        maybeAutoConnectVpn()
        // 用户打开过推送提醒：确保监听服务在跑（被系统杀掉/强制停止后重新拉起）
        if (NotifyPrefs.isEnabled(this) && !NotifyService.isRunning) {
            try { NotifyService.start(this) } catch (_: Exception) {}
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleNotificationIntent(intent)) {
            val m = MachineStore.selected(this)
            if (m != null && current?.id == m.id && webView.url != null) {
                // 同一台机器、页面已在：直接切会话
                current = m
                injectSelectSession()
            } else if (m != null) {
                current = m
                webView.loadUrl(m.baseUrl)
            }
        }
    }

    /** 通知点击：切到对应机器并记住要打开的会话。返回是否带了通知参数。 */
    private fun handleNotificationIntent(intent: Intent?): Boolean {
        val machineId = intent?.getLongExtra(NotifyService.EXTRA_MACHINE_ID, -1L) ?: -1L
        val sessionId = intent?.getStringExtra(NotifyService.EXTRA_SESSION_ID)
        if (machineId < 0 && sessionId.isNullOrBlank()) return false
        if (machineId >= 0 && MachineStore.load(this).any { it.id == machineId }) {
            MachineStore.setSelected(this, machineId)
        }
        pendingSessionId = sessionId?.takeIf { it.isNotBlank() }
        // 清掉 extras，避免旋转屏幕/重建时重复处理
        intent?.removeExtra(NotifyService.EXTRA_MACHINE_ID)
        intent?.removeExtra(NotifyService.EXTRA_SESSION_ID)
        return true
    }

    /** 等 window.app 就绪后切到目标会话（500ms 轮询，最多 20 次）。 */
    private fun injectSelectSession() {
        val id = pendingSessionId ?: return
        pendingSessionId = null
        if (!Regex("^[A-Za-z0-9_-]{1,100}$").matches(id)) return
        webView.evaluateJavascript(
            """
            (function(){
              var n = 0;
              var t = setInterval(function(){
                n++;
                try {
                  if (window.app && typeof app.selectSession === 'function' && app.sessions && app.sessions.size > 0) {
                    clearInterval(t);
                    if (app.sessions.has('$id')) { app.selectSession('$id'); }
                  }
                } catch(e) {}
                if (n > 20) clearInterval(t);
              }, 500);
            })();
            """.trimIndent(), null
        )
    }

    /** 悬浮球：拖动换位置，点击弹菜单，长按打开文本选择 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubble() {
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false
        var longFired = false
        val longPress = Runnable {
            longFired = true
            bubble.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            openTextSelect()
        }
        bubble.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startTx = v.translationX; startTy = v.translationY
                    dragging = false; longFired = false
                    v.postDelayed(longPress, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (dragging || abs(dx) > 12 || abs(dy) > 12) {
                        v.removeCallbacks(longPress)
                        dragging = true
                        v.translationX = startTx + dx
                        v.translationY = startTy + dy
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    if (!dragging && !longFired && ev.actionMasked == MotionEvent.ACTION_UP) showMenu(v)
                    true
                }
                else -> false
            }
        }
    }

    /** 抓取终端缓冲区文本，放进原生可选择文本框（长按选择/系统复制菜单） */
    private fun openTextSelect() {
        webView.evaluateJavascript(
            """
            (function(){
              try {
                var b = app.terminal.buffer.active;
                var out = [];
                for (var i = 0; i < b.length; i++) {
                  var l = b.getLine(i);
                  if (l) out.push(l.translateToString(true));
                }
                return out.join('\n').replace(/\n+${'$'}/, '');
              } catch(e) { return ''; }
            })();
            """.trimIndent()
        ) { result ->
            val text = try {
                org.json.JSONTokener(result).nextValue() as? String ?: ""
            } catch (_: Exception) { "" }
            if (text.isBlank()) {
                Toast.makeText(this, R.string.no_terminal_text, Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            showTextSelectDialog(text)
        }
    }

    private fun showTextSelectDialog(text: String) {
        val tv = android.widget.TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(40, 24, 40, 24)
            setTextColor(0xFFCDD6F4.toInt())
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(tv)
            setBackgroundColor(0xFF1E1E2E.toInt())
            post { fullScroll(View.FOCUS_DOWN) }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.text_select_title)
            .setView(scroll)
            .setPositiveButton(R.string.copy_all) { _, _ ->
                ClipboardBridge().write(text)
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        val m = machineForWindow()
        if (m == null) {
            if (isSideWindow) finish() else startActivity(Intent(this, MachinesActivity::class.java))
            return
        }
        if (current?.id != m.id || webView.url == null) {
            current = m
            webView.loadUrl(if (webView.url == null) startUrl(m) else m.baseUrl)
        } else {
            current = m
        }
        WgManager.refreshState(this)
        updateBubbleTint(WgManager.state)
    }

    private fun updateBubbleTint(st: Tunnel.State) {
        bubble.setColorFilter(if (st == Tunnel.State.UP) 0xFFA6E3A1.toInt() else 0xFFCDD6F4.toInt())
    }

    private fun maybeAutoConnectVpn() {
        if (!WgManager.isAutoConnect(this) || WgManager.getConfigText(this).isBlank()) return
        WgManager.refreshState(this)
        if (WgManager.state == Tunnel.State.UP) return
        val intent = VpnService.prepare(this)
        if (intent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VPN)
        } else {
            connectVpn()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == Activity.RESULT_OK) connectVpn()
    }

    private fun connectVpn() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WgManager.up(this@MainActivity) }
                Toast.makeText(this@MainActivity, "WireGuard 已连接", Toast.LENGTH_SHORT).show()
                webView.reload()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "WireGuard 连接失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showMenu(anchor: View) {
        val machines = MachineStore.load(this)
        val popup = PopupMenu(this, anchor)
        // 机器列表（带当前标记），itemId = 100 + index
        machines.forEachIndexed { i, m ->
            val label = (if (m.id == current?.id) "✓ " else "    ") + m.name
            popup.menu.add(0, 100 + i, i, label)
        }
        popup.menu.add(0, 9, 97, getString(R.string.menu_session_window))
        popup.menu.add(0, 8, 98, getString(R.string.menu_new_window))
        popup.menu.add(0, 4, 99, getString(R.string.menu_font_bigger))
        popup.menu.add(0, 5, 100, getString(R.string.menu_font_smaller))
        popup.menu.add(0, 6, 101, getString(R.string.menu_line_height, prefs().getFloat("lineHeight", 1.0f)))
        popup.menu.add(0, 1, 102, getString(R.string.menu_machines))
        popup.menu.add(0, 2, 103, getString(R.string.menu_wireguard))
        popup.menu.add(0, 7, 104, getString(R.string.menu_notify))
        popup.menu.add(0, 3, 105, getString(R.string.menu_reload))
        popup.setOnMenuItemClickListener {
            when {
                it.itemId >= 100 -> {
                    chooseMachine(machines[it.itemId - 100])
                }
                it.itemId == 8 -> current?.let { m -> openSideWindow(m.baseUrl, m) }
                it.itemId == 9 -> openActiveSessionWindow()
                it.itemId == 1 -> startActivity(Intent(this, MachinesActivity::class.java))
                it.itemId == 2 -> startActivity(Intent(this, WgActivity::class.java))
                it.itemId == 3 -> webView.reload()
                it.itemId == 4 -> webView.evaluateJavascript(
                    "try{app.increaseFontSize()}catch(e){}", null
                )
                it.itemId == 5 -> webView.evaluateJavascript(
                    "try{app.decreaseFontSize()}catch(e){}", null
                )
                it.itemId == 6 -> cycleLineHeight()
                it.itemId == 7 -> startActivity(Intent(this, NotifyActivity::class.java))
            }
            true
        }
        popup.show()
    }

    private fun injectClipboardPolyfill() {
        webView.evaluateJavascript(
            """
            (function(){
              if (window.__nativeClipInstalled) return 'OK'; window.__nativeClipInstalled = true;
              var w = function(t){ AndroidClipboard.write(String(t)); return Promise.resolve(); };
              var r = function(){ return Promise.resolve(AndroidClipboard.read()); };
              try {
                if (!navigator.clipboard) Object.defineProperty(navigator, 'clipboard', {value:{}, configurable:true});
                navigator.clipboard.writeText = w;
                navigator.clipboard.readText = r;
              } catch(e) {}
              return (navigator.clipboard && typeof navigator.clipboard.writeText === 'function'
                      && typeof AndroidClipboard !== 'undefined') ? 'OK' : 'FAIL';
            })();
            """.trimIndent()
        ) { result ->
            if (result?.contains("OK") != true) {
                Toast.makeText(this, R.string.clipboard_bridge_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Codeman 未暴露行距设置，直接操作 xterm 的 lineHeight；terminal 异步创建，带重试 */
    private fun applyLineHeight(value: Float) {
        if (value == 1.0f) return
        webView.evaluateJavascript(
            """
            (function(){
              var n = 0;
              var t = setInterval(function(){
                n++;
                try {
                  if (window.app && app.terminal) {
                    app.terminal.options.lineHeight = $value;
                    if (app.fitAddon) app.fitAddon.fit();
                    clearInterval(t);
                  }
                } catch(e) {}
                if (n > 20) clearInterval(t);
              }, 500);
            })();
            """.trimIndent(), null
        )
    }

    private fun cycleLineHeight() {
        val cur = prefs().getFloat("lineHeight", 1.0f)
        val idx = LINE_HEIGHTS.indexOfFirst { it >= cur - 0.01f }.coerceAtLeast(0)
        val next = LINE_HEIGHTS[(idx + 1) % LINE_HEIGHTS.size]
        prefs().edit().putFloat("lineHeight", next).apply()
        webView.evaluateJavascript(
            "try{app.terminal.options.lineHeight=$next; app.fitAddon&&app.fitAddon.fit();}catch(e){}", null
        )
        Toast.makeText(this, getString(R.string.line_height_set, next), Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else if (isSideWindow) finishAndRemoveTask()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        if (isSideWindow) {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }
}
