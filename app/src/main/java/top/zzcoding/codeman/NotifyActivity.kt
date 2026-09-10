package top.zzcoding.codeman

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

/** 推送提醒设置：总开关、事件类型、按机器开关、电池优化、连接状态。 */
class NotifyActivity : AppCompatActivity() {

    private lateinit var masterSwitch: SwitchMaterial
    private lateinit var statusView: TextView
    private lateinit var machinesBox: LinearLayout
    private lateinit var batteryBtn: Button

    companion object {
        private const val REQ_POST_NOTIF = 200
        private val EVENT_LABELS = mapOf(
            "permission" to R.string.ev_permission,
            "question" to R.string.ev_question,
            "idle" to R.string.ev_idle,
            "stop" to R.string.ev_stop,
            "task" to R.string.ev_task,
            "error" to R.string.ev_error,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notify)
        title = getString(R.string.menu_notify)

        masterSwitch = findViewById(R.id.switch_master)
        statusView = findViewById(R.id.notify_status)
        machinesBox = findViewById(R.id.machines_box)
        batteryBtn = findViewById(R.id.btn_battery)

        masterSwitch.isChecked = NotifyPrefs.isEnabled(this)
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            NotifyPrefs.setEnabled(this, checked)
            if (checked) enableService() else NotifyService.stop(this)
            render()
        }

        val eventsBox = findViewById<LinearLayout>(R.id.events_box)
        for (key in NotifyPrefs.EVENT_KEYS) {
            val sw = SwitchMaterial(this).apply {
                text = getString(EVENT_LABELS[key] ?: R.string.ev_permission)
                isChecked = NotifyPrefs.isEventEnabled(this@NotifyActivity, key)
                setOnCheckedChangeListener { _, c -> NotifyPrefs.setEventEnabled(this@NotifyActivity, key, c) }
            }
            eventsBox.addView(sw)
        }

        for (m in MachineStore.load(this)) {
            val sw = SwitchMaterial(this).apply {
                text = "${m.name}  (${m.host}:${m.port})"
                isChecked = NotifyPrefs.isMachineEnabled(this@NotifyActivity, m.id)
                setOnCheckedChangeListener { _, c ->
                    NotifyPrefs.setMachineEnabled(this@NotifyActivity, m.id, c)
                    NotifyService.refresh(this@NotifyActivity)
                }
            }
            machinesBox.addView(sw)
        }

        batteryBtn.setOnClickListener { requestIgnoreBattery() }
        findViewById<Button>(R.id.btn_test).setOnClickListener {
            NotifyService.refresh(this)
            Toast.makeText(this, R.string.notify_refreshed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        NotifyService.onStatesChanged = { render() }
        render()
    }

    override fun onPause() {
        NotifyService.onStatesChanged = null
        super.onPause()
    }

    private fun enableService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIF)
            return
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Toast.makeText(this, R.string.notify_blocked, Toast.LENGTH_LONG).show()
        }
        NotifyService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIF) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                NotifyService.start(this)
            } else {
                Toast.makeText(this, R.string.notify_permission_denied, Toast.LENGTH_LONG).show()
                NotifyService.start(this) // 服务照跑，系统只是不显示通知；用户在系统设置里打开即可
            }
            render()
        }
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, R.string.battery_already, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun render() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        batteryBtn.text = getString(
            if (pm.isIgnoringBatteryOptimizations(packageName)) R.string.battery_ok else R.string.battery_request
        )
        val sb = StringBuilder()
        if (!NotifyService.isRunning) {
            sb.append(getString(R.string.notify_service_off))
        } else {
            val machines = MachineStore.load(this)
            for (m in machines) {
                val st = NotifyService.machineStates[m.id] ?: getString(R.string.notify_state_disabled)
                sb.append(m.name).append("：").append(st).append('\n')
            }
            if (machines.isEmpty()) sb.append(getString(R.string.notify_no_machines))
        }
        statusView.text = sb.toString().trimEnd()
    }
}
