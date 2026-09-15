package top.zzcoding.codeman

import android.os.Bundle

/**
 * 侧边窗口：可同时开多个（每个一个任务），固定显示打开它时的那台机器。
 * 典型用法：左边主窗口看会话 A，右边侧边窗口看会话 B / 文件预览。
 */
class WindowActivity : MainActivity() {

    companion object {
        const val EXTRA_URL = "window_url"
        const val EXTRA_MACHINE_ID = "window_machine_id"
    }

    private var machineId = -1L

    override val isSideWindow = true

    override fun onCreate(savedInstanceState: Bundle?) {
        machineId = savedInstanceState?.getLong(EXTRA_MACHINE_ID, -1L)
            ?: intent.getLongExtra(EXTRA_MACHINE_ID, -1L)
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(EXTRA_MACHINE_ID, machineId)
    }

    override fun machineForWindow(): Machine? =
        MachineStore.load(this).firstOrNull { it.id == machineId }

    override fun startUrl(m: Machine): String {
        val url = intent.getStringExtra(EXTRA_URL) ?: return m.baseUrl
        return if (url.startsWith(m.baseUrl)) url else m.baseUrl
    }

    /** 侧边窗口切机器只影响自己，不改主窗口的全局选择 */
    override fun chooseMachine(m: Machine) {
        machineId = m.id
        current = m
        loadInWindow(m.baseUrl)
    }
}
