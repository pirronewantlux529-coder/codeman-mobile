package top.zzcoding.codeman

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Machine(
    var id: Long,
    var name: String,
    var host: String,
    var port: Int,
    var useHttps: Boolean,
    var username: String,
    var password: String,
) {
    val baseUrl: String
        get() = (if (useHttps) "https" else "http") + "://$host:$port/"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("useHttps", useHttps)
        put("username", username)
        put("password", password)
    }

    companion object {
        fun fromJson(o: JSONObject) = Machine(
            id = o.optLong("id"),
            name = o.optString("name"),
            host = o.optString("host"),
            port = o.optInt("port", 8095),
            useHttps = o.optBoolean("useHttps", false),
            username = o.optString("username", "admin"),
            password = o.optString("password"),
        )
    }
}

object MachineStore {
    private const val PREFS = "machines"

    fun load(ctx: Context): MutableList<Machine> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "[]")!!
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { Machine.fromJson(arr.getJSONObject(it)) }
    }

    fun save(ctx: Context, machines: List<Machine>) {
        val arr = JSONArray()
        machines.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", arr.toString()).apply()
    }

    fun selectedId(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("selected", -1L)

    fun setSelected(ctx: Context, id: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("selected", id).apply()
    }

    fun selected(ctx: Context): Machine? {
        val list = load(ctx)
        if (list.isEmpty()) return null
        val id = selectedId(ctx)
        return list.firstOrNull { it.id == id } ?: list.first()
    }
}
