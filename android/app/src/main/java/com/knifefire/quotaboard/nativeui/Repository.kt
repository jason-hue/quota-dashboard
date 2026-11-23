package com.knifefire.quotaboard.nativeui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 单个额度窗口 */
data class QuotaWindow(
    val short: String,
    val unit: Int,
    val remainPct: Int,
    val usedPct: Int,
    val cap: Double,
    val used: Double,
    val resetAt: String?
)

/** 一个账号（原生核心页数据模型，字段对齐旧 WebView 版） */
data class Account(
    val id: String,
    val name: String,
    val provider: String,
    val token: String,
    val authMode: String = "auto",
    val org: String = "",
    val project: String = "",
    val planType: String = "",
    val lastAt: Long = 0,
    val level: String = "",
    val windows: List<QuotaWindow> = emptyList(),
    val balanceTotal: Double = 0.0,
    val balanceCurrency: String = "CNY",
    val error: String? = null
) {
    /** 最紧张窗口（剩余百分比最低） */
    val primary: QuotaWindow? get() = windows.minByOrNull { it.remainPct }
}

/** 本机存储：SharedPreferences + JSON，凭证只存设备本地 */
class Repository(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("native-board-v1", Context.MODE_PRIVATE)

    companion object {
        const val DEBUG_SEED = false // 示例数据开关，交付保持 false

        fun demoAccounts(): List<Account> = listOf(
            Account(
                id = "demo-1", name = "13277585569", provider = "glm-cn", token = "", level = "pro",
                windows = listOf(
                    QuotaWindow("5小时", 3, 100, 0, 100.0, 0.0, null),
                    QuotaWindow("本周", 6, 0, 100, 100.0, 100.0, null)
                )
            ),
            Account(
                id = "demo-2", name = "GLM 备用号", provider = "glm-global", token = "", level = "pro",
                windows = listOf(
                    QuotaWindow("5小时", 3, 78, 22, 100.0, 22.0, null),
                    QuotaWindow("本周", 6, 62, 38, 100.0, 38.0, null)
                )
            ),
            Account(
                id = "demo-3", name = "DeepSeek", provider = "deepseek", token = "",
                balanceTotal = 5.81, balanceCurrency = "CNY"
            )
        )
    }

    fun loadAccounts(): List<Account> {
        // 开发期示例数据开关：验证卡片/拖拽渲染，交付前置 false
        if (DEBUG_SEED) return demoAccounts()
        val raw = prefs.getString("accounts", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> parseAccount(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAccounts(list: List<Account>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("accounts", arr.toString()).apply()
    }

    fun getThemeMode(): String = prefs.getString("themeMode", "brand") ?: "brand"
    fun setThemeMode(mode: String) { prefs.edit().putString("themeMode", mode).apply() }

    private fun parseAccount(o: JSONObject): Account {
        val wins = mutableListOf<QuotaWindow>()
        val wa = o.optJSONArray("windows")
        if (wa != null) for (i in 0 until wa.length()) {
            val w = wa.getJSONObject(i)
            wins.add(QuotaWindow(
                short = w.optString("short", ""),
                unit = w.optInt("unit", 0),
                remainPct = w.optInt("remainPct", 0),
                usedPct = w.optInt("usedPct", 0),
                cap = w.optDouble("cap", 0.0),
                used = w.optDouble("used", 0.0),
                resetAt = w.optString("resetAt", "").ifEmpty { null }
            ))
        }
        return Account(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            provider = o.optString("provider", "glm-cn"),
            token = o.optString("token", ""),
            authMode = o.optString("authMode", "auto"),
            org = o.optString("org", ""),
            project = o.optString("project", ""),
            planType = o.optString("planType", ""),
            lastAt = o.optLong("lastAt", 0),
            level = o.optString("level", ""),
            windows = wins,
            balanceTotal = o.optDouble("balanceTotal", 0.0),
            balanceCurrency = o.optString("balanceCurrency", "CNY"),
            error = o.optString("error", "").ifEmpty { null }
        )
    }

    private fun toJson(a: Account): JSONObject = JSONObject().apply {
        put("id", a.id); put("name", a.name); put("provider", a.provider); put("token", a.token)
        put("authMode", a.authMode); put("org", a.org); put("project", a.project); put("planType", a.planType)
        put("lastAt", a.lastAt); put("level", a.level)
        put("balanceTotal", a.balanceTotal); put("balanceCurrency", a.balanceCurrency)
        a.error?.let { put("error", it) }
        val arr = JSONArray()
        a.windows.forEach { w ->
            arr.put(JSONObject().apply {
                put("short", w.short); put("unit", w.unit); put("remainPct", w.remainPct)
                put("usedPct", w.usedPct); put("cap", w.cap); put("used", w.used)
                w.resetAt?.let { put("resetAt", it) }
            })
        }
        put("windows", arr)
    }
}
