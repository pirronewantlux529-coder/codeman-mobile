package top.zzcoding.codeman

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.VpnService
import android.net.http.SslError
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.HttpAuthHandler
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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bubble: ImageButton
    private var current: Machine? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val REQ_VPN = 100
    }

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
            }
        }

        WgManager.onStateChanged = { st ->
            runOnUiThread { updateBubbleTint(st) }
        }

        maybeAutoConnectVpn()
    }

    /** 悬浮球：拖动换位置，点击弹菜单 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubble() {
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false
        bubble.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startTx = v.translationX; startTy = v.translationY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (dragging || abs(dx) > 12 || abs(dy) > 12) {
                        dragging = true
                        v.translationX = startTx + dx
                        v.translationY = startTy + dy
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) showMenu(v)
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val m = MachineStore.selected(this)
        if (m == null) {
            startActivity(Intent(this, MachinesActivity::class.java))
            return
        }
        if (current?.id != m.id || webView.url == null) {
            current = m
            webView.loadUrl(m.baseUrl)
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
        popup.menu.add(0, 1, 100, getString(R.string.menu_machines))
        popup.menu.add(0, 2, 101, getString(R.string.menu_wireguard))
        popup.menu.add(0, 3, 102, getString(R.string.menu_reload))
        popup.setOnMenuItemClickListener {
            when {
                it.itemId >= 100 -> {
                    val m = machines[it.itemId - 100]
                    MachineStore.setSelected(this, m.id)
                    current = m
                    webView.loadUrl(m.baseUrl)
                }
                it.itemId == 1 -> startActivity(Intent(this, MachinesActivity::class.java))
                it.itemId == 2 -> startActivity(Intent(this, WgActivity::class.java))
                it.itemId == 3 -> webView.reload()
            }
            true
        }
        popup.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
