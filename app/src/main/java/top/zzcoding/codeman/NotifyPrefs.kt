package top.zzcoding.codeman

import android.content.Context
import android.content.SharedPreferences

/** 推送提醒偏好（SharedPreferences "notify"）。 */
object NotifyPrefs {
    private const val PREFS = "notify"

    /** 事件类型：key 与 [NotifyService] 的事件映射一致。 */
    val EVENT_KEYS = listOf("permission", "question", "idle", "stop", "task", "error")

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("enabled", v).apply()

    fun isEventEnabled(ctx: Context, key: String): Boolean = p(ctx).getBoolean("event_$key", true)
    fun setEventEnabled(ctx: Context, key: String, v: Boolean) =
        p(ctx).edit().putBoolean("event_$key", v).apply()

    fun isMachineEnabled(ctx: Context, machineId: Long): Boolean =
        p(ctx).getBoolean("machine_$machineId", true)

    fun setMachineEnabled(ctx: Context, machineId: Long, v: Boolean) =
        p(ctx).edit().putBoolean("machine_$machineId", v).apply()
}
