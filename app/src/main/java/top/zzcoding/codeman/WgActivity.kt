package top.zzcoding.codeman

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WgActivity : AppCompatActivity() {

    private lateinit var configEdit: EditText
    private lateinit var statusView: TextView
    private lateinit var toggleBtn: Button
    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val REQ_VPN = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wg)
        title = getString(R.string.menu_wireguard)

        configEdit = findViewById(R.id.wg_config)
        statusView = findViewById(R.id.wg_status)
        toggleBtn = findViewById(R.id.btn_toggle)
        val autoSwitch = findViewById<SwitchMaterial>(R.id.switch_auto)

        configEdit.setText(WgManager.getConfigText(this))
        autoSwitch.isChecked = WgManager.isAutoConnect(this)
        autoSwitch.setOnCheckedChangeListener { _, checked -> WgManager.setAutoConnect(this, checked) }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            WgManager.setConfigText(this, configEdit.text.toString())
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }

        toggleBtn.setOnClickListener {
            WgManager.setConfigText(this, configEdit.text.toString())
            if (WgManager.state == Tunnel.State.UP) doDown() else requestVpnThenUp()
        }

        WgManager.onStateChanged = { runOnUiThread { render() } }
        WgManager.refreshState(this)
        render()
    }

    private fun render() {
        val up = WgManager.state == Tunnel.State.UP
        statusView.text = if (up) getString(R.string.wg_connected) else getString(R.string.wg_disconnected)
        toggleBtn.text = if (up) getString(R.string.wg_disconnect) else getString(R.string.wg_connect)
    }

    private fun requestVpnThenUp() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VPN)
        } else doUp()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == Activity.RESULT_OK) doUp()
    }

    private fun doUp() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WgManager.up(this@WgActivity) }
            } catch (e: Exception) {
                Toast.makeText(this@WgActivity, "连接失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            render()
        }
    }

    private fun doDown() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WgManager.down(this@WgActivity) }
            } catch (e: Exception) {
                Toast.makeText(this@WgActivity, "断开失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            render()
        }
    }
}
