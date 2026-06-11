package top.zzcoding.codeman

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

object WgManager {
    private const val PREFS = "wireguard"
    private var backend: GoBackend? = null

    @Volatile
    var state: Tunnel.State = Tunnel.State.DOWN
        private set

    var onStateChanged: ((Tunnel.State) -> Unit)? = null

    private val tunnel = object : Tunnel {
        override fun getName() = "codeman"
        override fun onStateChange(newState: Tunnel.State) {
            state = newState
            onStateChanged?.invoke(newState)
        }
    }

    private fun backend(ctx: Context): GoBackend {
        return backend ?: GoBackend(ctx.applicationContext).also { backend = it }
    }

    fun getConfigText(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("config", "")!!

    fun setConfigText(ctx: Context, text: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("config", text).apply()
    }

    fun isAutoConnect(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto", false)

    fun setAutoConnect(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto", v).apply()
    }

    /** Blocking — call from a background thread. Throws on bad config / backend errors. */
    fun up(ctx: Context) {
        val text = getConfigText(ctx)
        require(text.isNotBlank()) { "WireGuard 配置为空" }
        val config = Config.parse(BufferedReader(StringReader(text)))
        backend(ctx).setState(tunnel, Tunnel.State.UP, config)
    }

    /** Blocking — call from a background thread. */
    fun down(ctx: Context) {
        backend(ctx).setState(tunnel, Tunnel.State.DOWN, null)
    }

    fun refreshState(ctx: Context) {
        try {
            state = backend(ctx).getState(tunnel)
        } catch (_: Exception) {
        }
    }
}
