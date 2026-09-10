package top.zzcoding.codeman

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机后恢复通知监听（仅当用户打开过推送提醒）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!NotifyPrefs.isEnabled(context)) return
        try {
            NotifyService.start(context)
        } catch (_: Exception) {
        }
    }
}
