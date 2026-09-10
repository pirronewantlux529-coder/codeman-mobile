package top.zzcoding.codeman

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * 前台服务：对每台已启用的机器保持一条 Codeman SSE 连接，把 hook 事件
 * （需要确认/提问/等待输入/回复完成/任务完成/会话出错）转成系统通知。
 */
class NotifyService : Service() {

    companion object {
        const val ACTION_START = "top.zzcoding.codeman.notify.START"
        const val ACTION_STOP = "top.zzcoding.codeman.notify.STOP"
        const val ACTION_REFRESH = "top.zzcoding.codeman.notify.REFRESH"

        const val EXTRA_MACHINE_ID = "machineId"
        const val EXTRA_SESSION_ID = "sessionId"

        const val CH_ALERT = "codeman_alert"
        const val CH_EVENT = "codeman_event"
        const val CH_SERVICE = "codeman_service"

        private const val STATUS_NOTIFICATION_ID = 100
        private const val EVENT_NOTIFICATION_ID = 1
        private const val DEBOUNCE_MS = 5_000L

        @Volatile var isRunning: Boolean = false
            private set

        /** machineId → 连接状态文案，供设置页展示。 */
        val machineStates: MutableMap<Long, String> = java.util.concurrent.ConcurrentHashMap()

        /** 设置页注册，状态变化时在主线程回调。 */
        @Volatile var onStatesChanged: (() -> Unit)? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, NotifyService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, NotifyService::class.java).setAction(ACTION_STOP))
        }

        fun refresh(ctx: Context) {
            if (isRunning) ctx.startService(Intent(ctx, NotifyService::class.java).setAction(ACTION_REFRESH))
        }

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, ctx.getString(R.string.ch_alert), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = ctx.getString(R.string.ch_alert_desc)
                    enableVibration(true)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_EVENT, ctx.getString(R.string.ch_event), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = ctx.getString(R.string.ch_event_desc)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_SERVICE, ctx.getString(R.string.ch_service), NotificationManager.IMPORTANCE_MIN).apply {
                    description = ctx.getString(R.string.ch_service_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val clients = mutableMapOf<Long, SseClient>()
    /** machineId → (sessionId → 展示名) */
    private val sessionNames = java.util.concurrent.ConcurrentHashMap<Long, MutableMap<String, String>>()
    /** "machineId:sessionId:kind" → 上次通知时间 */
    private val lastNotified = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(clients) { clients.values.forEach { it.wake() } }
            }
        }
        try { cm.registerDefaultNetworkCallback(networkCallback!!) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopClients()
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // START / REFRESH / 系统重建（intent 为 null）
                isRunning = true
                startForegroundCompat(buildStatusNotification())
                rebuildClients()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopClients()
        isRunning = false
        networkCallback?.let {
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(STATUS_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(STATUS_NOTIFICATION_ID, n)
        }
    }

    // ── 连接管理 ─────────────────────────────────────────────────────────

    private fun rebuildClients() {
        val wanted = MachineStore.load(this).filter { NotifyPrefs.isMachineEnabled(this, it.id) }
        synchronized(clients) {
            // 停掉已删除/已禁用/配置变了的
            val keep = wanted.associateBy { it.id }
            clients.keys.toList().forEach { id ->
                val m = keep[id]
                val c = clients[id]!!
                if (m == null || m.toJson().toString() != c.machine.toJson().toString()) {
                    c.stop()
                    clients.remove(id)
                    machineStates.remove(id)
                    sessionNames.remove(id)
                }
            }
            wanted.forEach { m ->
                if (!clients.containsKey(m.id)) {
                    val c = SseClient(
                        m,
                        onEvent = { ev, data -> handleEvent(m, ev, data) },
                        onState = { st -> onClientState(m, st) },
                    )
                    clients[m.id] = c
                    machineStates[m.id] = getString(R.string.notify_state_connecting)
                    c.start()
                }
            }
        }
        publishStates()
    }

    private fun stopClients() {
        synchronized(clients) {
            clients.values.forEach { it.stop() }
            clients.clear()
        }
        machineStates.clear()
        sessionNames.clear()
        publishStates()
    }

    private fun onClientState(m: Machine, st: SseClient.State) {
        val c = synchronized(clients) { clients[m.id] }
        machineStates[m.id] = when (st) {
            SseClient.State.CONNECTED -> getString(R.string.notify_state_connected)
            SseClient.State.CONNECTING -> getString(R.string.notify_state_connecting)
            SseClient.State.RECONNECTING -> getString(R.string.notify_state_reconnecting, c?.lastError ?: "")
            SseClient.State.STOPPED -> getString(R.string.notify_state_stopped)
        }
        publishStates()
    }

    private fun publishStates() {
        main.post {
            if (isRunning) {
                try {
                    NotificationManagerCompat.from(this).notify(STATUS_NOTIFICATION_ID, buildStatusNotification())
                } catch (_: SecurityException) {}
            }
            onStatesChanged?.invoke()
        }
    }

    // ── 事件处理 ─────────────────────────────────────────────────────────

    private fun handleEvent(m: Machine, event: String, data: String) {
        val obj = try { JSONObject(data) } catch (_: Exception) { return }
        when (event) {
            "init" -> rememberSessions(m, obj.optJSONArray("sessions"))
            "session:created", "session:updated" -> rememberSession(m, obj)
            "session:deleted" -> sessionNames[m.id]?.remove(obj.optString("id"))
            "hook:permission_prompt" -> {
                val tool = obj.optString("tool_name", "")
                val input = obj.optJSONObject("tool_input")
                val detail = input?.let { i ->
                    listOf("command", "file_path", "description").firstNotNullOfOrNull { k ->
                        i.optString(k, "").takeIf { it.isNotBlank() }
                    }
                }
                val body = when {
                    tool.isNotBlank() && detail != null -> "$tool: $detail"
                    tool.isNotBlank() -> tool
                    else -> obj.optString("message", "").ifBlank { getString(R.string.notify_body_permission) }
                }
                notifyEvent(m, obj, "permission", CH_ALERT, R.string.notify_title_permission, body)
            }
            "hook:elicitation_dialog" -> notifyEvent(
                m, obj, "question", CH_ALERT, R.string.notify_title_question,
                obj.optString("message", "").ifBlank { getString(R.string.notify_body_question) }
            )
            "hook:idle_prompt" -> notifyEvent(
                m, obj, "idle", CH_EVENT, R.string.notify_title_idle,
                obj.optString("message", "").ifBlank { getString(R.string.notify_body_idle) }
            )
            "hook:stop" -> notifyEvent(
                m, obj, "stop", CH_EVENT, R.string.notify_title_stop,
                obj.optString("message", "").ifBlank { getString(R.string.notify_body_stop) }
            )
            "hook:task_completed" -> notifyEvent(
                m, obj, "task", CH_EVENT, R.string.notify_title_task,
                obj.optString("message", "").ifBlank { getString(R.string.notify_body_task) }
            )
            "session:error" -> notifyEvent(
                m, obj, "error", CH_ALERT, R.string.notify_title_error,
                obj.optString("error", "").ifBlank { getString(R.string.notify_body_error) }
            )
            "session:exit" -> notifyEvent(
                m, obj, "error", CH_EVENT, R.string.notify_title_exit,
                getString(R.string.notify_body_exit, obj.optInt("code", -1))
            )
        }
    }

    private fun rememberSessions(m: Machine, arr: JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) rememberSession(m, arr.optJSONObject(i) ?: continue)
    }

    private fun rememberSession(m: Machine, s: JSONObject) {
        val id = s.optString("id", "")
        if (id.isBlank()) return
        val name = s.optString("name", "").ifBlank {
            s.optString("workingDir", "").trimEnd('/').substringAfterLast('/').ifBlank { id.take(8) }
        }
        sessionNames.getOrPut(m.id) { java.util.concurrent.ConcurrentHashMap() }[id] = name
    }

    private fun sessionName(m: Machine, sessionId: String): String =
        sessionNames[m.id]?.get(sessionId) ?: sessionId.take(8).ifBlank { "?" }

    private fun notifyEvent(m: Machine, obj: JSONObject, kind: String, channel: String, titleRes: Int, body: String) {
        if (!NotifyPrefs.isEventEnabled(this, kind)) return
        val sessionId = obj.optString("sessionId", "").ifBlank { obj.optString("id", "") }
        val key = "${m.id}:$sessionId:$kind"
        val now = System.currentTimeMillis()
        val last = lastNotified[key] ?: 0L
        if (now - last < DEBOUNCE_MS) return
        lastNotified[key] = now
        if (lastNotified.size > 500) lastNotified.entries.removeIf { now - it.value > 60_000 }

        val sName = sessionName(m, sessionId)
        val title = getString(titleRes, sName)
        val tag = "${m.id}:$sessionId"
        val open = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_MACHINE_ID, m.id)
            putExtra(EXTRA_SESSION_ID, sessionId)
            data = android.net.Uri.parse("codeman://${m.id}/$sessionId")
        }
        val pi = PendingIntent.getActivity(
            this, tag.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_codeman)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(m.name)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setWhen(now)
            .setCategory(if (channel == CH_ALERT) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (channel == CH_ALERT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(if (channel == CH_ALERT) NotificationCompat.DEFAULT_ALL else NotificationCompat.DEFAULT_LIGHTS)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(tag, EVENT_NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // Android 13+ 未授予 POST_NOTIFICATIONS
        }
    }

    // ── 常驻通知 ─────────────────────────────────────────────────────────

    private fun buildStatusNotification(): Notification {
        val total = synchronized(clients) { clients.size }
        val connected = synchronized(clients) { clients.values.count { it.state == SseClient.State.CONNECTED } }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, NotifyActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_codeman)
            .setContentTitle(getString(R.string.notify_service_title, total))
            .setContentText(getString(R.string.notify_service_text, connected, total))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
