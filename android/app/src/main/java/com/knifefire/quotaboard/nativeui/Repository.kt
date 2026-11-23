package com.knifefire.quotaboard.nativeui

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 单个额度窗口 */
data class QuotaWindow(
    val short: String,
    val unit: Int,
    val remainPct: Int,
    val usedPct: Int,
    val cap: Double,
    val used: Double,
    val resetAt: String?
) {
    companion object {
        /** 与旧 WebView 版 parseWindow 的短标签规则保持一致 */
        fun shortOf(unit: Int, number: Int): String = when (unit) {
            6 -> "本周"
            3 -> "${if (number > 0) number else 5}小时"
            5 -> "本月"
            1 -> if (number > 1) "${number}天" else "每天"
            else -> "${if (number > 1) number else ""}${unitName(unit)}"
        }
        private fun unitName(u: Int) = when (u) {
            3 -> "小时"; 6 -> "周"; 5 -> "月"; 1 -> "天"; else -> "单位$u"
        }
        private val RANK = mapOf(3 to 0, 1 to 1, 6 to 2, 5 to 3)
        fun sort(wins: List<QuotaWindow>): List<QuotaWindow> =
            wins.sortedBy { RANK[it.unit] ?: 9 }
    }
}

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
    val error: String? = null,
    val raw: String? = null
) {
    /** 最紧张窗口（剩余百分比最低） */
    val primary: QuotaWindow? get() = windows.minByOrNull { it.remainPct }
}

data class Settings(
    val autoRefresh: Boolean = true,
    val intervalMin: Int = 5,
    val autoImport: Boolean = true
)

data class ImportResult(val ok: Boolean, val message: String, val count: Int = 0)

/** 本机存储：SharedPreferences + JSON，凭证只存设备本地 */
class Repository(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("native-board-v1", Context.MODE_PRIVATE)

    companion object {
        const val DEBUG_SEED = false // 示例数据开关，交付保持 false
        const val BACKUP_PREFIX = "QBOARD1:"
        private const val LEGACY_KEY = "quota-board-v1"

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

    // ---------------- 账号 ----------------
    fun loadAccounts(): List<Account> {
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

    // ---------------- 设置 ----------------
    fun getThemeMode(): String = prefs.getString("themeMode", "brand") ?: "brand"
    fun setThemeMode(mode: String) { prefs.edit().putString("themeMode", mode).apply() }

    fun loadSettings(): Settings = Settings(
        autoRefresh = prefs.getBoolean("autoRefresh", true),
        intervalMin = prefs.getInt("intervalMin", 5).coerceIn(1, 120),
        autoImport = prefs.getBoolean("autoImport", true)
    )

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putBoolean("autoRefresh", s.autoRefresh)
            .putInt("intervalMin", s.intervalMin.coerceIn(1, 120))
            .putBoolean("autoImport", s.autoImport)
            .apply()
    }

    fun isMigrated(): Boolean = prefs.getBoolean("legacyMigrated", false)
    fun markMigrated() { prefs.edit().putBoolean("legacyMigrated", true).apply() }

    // ---------------- 备份 / 恢复（与旧 WebView 版 QBOARD1: 格式双向兼容） ----------------
    fun exportBackup(accounts: List<Account>): String {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(JSONObject().apply {
                put("name", a.name); put("provider", a.provider); put("token", a.token)
                put("authMode", a.authMode); put("org", a.org); put("project", a.project)
                put("planType", a.planType)
            })
        }
        val data = JSONObject().apply {
            put("v", 1)
            put("exportedAt", System.currentTimeMillis())
            put("accounts", arr)
        }
        val b64 = Base64.encodeToString(
            data.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP
        )
        return BACKUP_PREFIX + b64
    }

    /** 只读解析备份，供剪贴板自动导入预检使用（不写存储） */
    fun peekBackupCount(text: String): Int = try { parseBackupAccounts(text).size } catch (e: Exception) { 0 }

    private fun parseBackupAccounts(text: String): List<Account> {
        val t = text.trim()
        if (!t.startsWith(BACKUP_PREFIX)) throw Exception("不是有效的备份内容（缺少标识头）")
        val json = String(
            Base64.decode(t.substring(BACKUP_PREFIX.length), Base64.NO_WRAP),
            StandardCharsets.UTF_8
        )
        val data = JSONObject(json)
        val arr = data.optJSONArray("accounts") ?: throw Exception("备份格式损坏")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name", "")
            val provider = o.optString("provider", "")
            val token = o.optString("token", "")
            if (name.isBlank() || provider.isBlank() || token.isBlank()) null
            else Account(
                id = UUID.randomUUID().toString(),
                name = name, provider = provider, token = token,
                authMode = o.optString("authMode", "auto"),
                org = o.optString("org", ""), project = o.optString("project", ""),
                planType = o.optString("planType", "")
            )
        }
    }

    /** mode: "merge" | "replace"；按 平台+名称+凭证 去重 */
    fun importBackup(text: String, mode: String, current: List<Account>): ImportResult {
        val incoming = try { parseBackupAccounts(text) } catch (e: Exception) {
            return ImportResult(false, "导入失败：${e.message}")
        }
        if (incoming.isEmpty()) return ImportResult(false, "备份里没有有效的账号。")
        return applyIncoming(incoming, mode, current)
    }

    private fun applyIncoming(incoming: List<Account>, mode: String, current: List<Account>): ImportResult {
        if (mode == "replace") {
            saveAccounts(incoming)
            return ImportResult(true, "已恢复 ${incoming.size} 个账号", incoming.size)
        }
        val seen = current.map { "${it.provider}|${it.name}|${it.token}" }.toMutableSet()
        val merged = current.toMutableList()
        var added = 0
        incoming.forEach { a ->
            val k = "${a.provider}|${a.name}|${a.token}"
            if (seen.add(k)) { merged.add(a); added++ }
        }
        if (added == 0) return ImportResult(false, "备份中的账号都已存在，无需重复导入。")
        saveAccounts(merged)
        return ImportResult(true, "已合并导入 $added 个账号", added)
    }

    /**
     * 迁移旧 WebView 版 localStorage 数据（key=quota-board-v1）。
     * 不仅迁移凭证，还把已缓存的额度窗口/余额一起带过来，避免切换入口后卡片变空。
     * 返回导入的账号数。
     */
    fun importLegacy(raw: String): Int {
        if (raw.isBlank()) return 0
        val root = try { JSONObject(raw) } catch (e: Exception) { return 0 }
        val arr = root.optJSONArray("accounts") ?: return 0
        val list = mutableListOf<Account>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "")
            val provider = o.optString("provider", "glm-cn")
            val token = o.optString("token", "")
            if (token.isBlank()) continue
            val result = o.optJSONObject("result")
            val wins = mutableListOf<QuotaWindow>()
            var level = ""
            var balance = 0.0
            var currency = "CNY"
            var error: String? = null
            if (result != null && result.optBoolean("ok", false)) {
                if (provider == "deepseek") {
                    val infos = result.optJSONObject("data")?.optJSONArray("balance_infos")
                    val p = infos?.optJSONObject(0)
                    balance = p?.optDouble("total_balance", 0.0) ?: 0.0
                    currency = p?.optString("currency", "CNY") ?: "CNY"
                } else {
                    val d = result.optJSONObject("quota")?.optJSONObject("data")
                    level = d?.optString("level", "") ?: ""
                    val limits = d?.optJSONArray("limits")
                    if (limits != null) for (j in 0 until limits.length()) {
                        val l = limits.optJSONObject(j) ?: continue
                        val usedPct = l.optDouble("percentage", 0.0).toInt()
                        val unit = l.optInt("unit", 0)
                        val number = l.optInt("number", 0)
                        wins.add(QuotaWindow(
                            short = QuotaWindow.shortOf(unit, number),
                            unit = unit,
                            usedPct = usedPct,
                            remainPct = (100 - usedPct).coerceIn(0, 100),
                            cap = l.optDouble("usage", 0.0),
                            used = l.optDouble("currentValue", 0.0),
                            resetAt = l.optString("nextResetTime", "").ifEmpty { null }
                        ))
                    }
                }
            } else if (result != null) {
                error = result.optString("error", "查询失败").ifBlank { "查询失败" }
            }
            list.add(Account(
                id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                name = name.ifBlank { "账号 ${i + 1}" },
                provider = provider, token = token,
                authMode = o.optString("authMode", "auto"),
                org = o.optString("org", ""), project = o.optString("project", ""),
                planType = o.optString("planType", ""),
                lastAt = o.optLong("lastAt", 0),
                level = level,
                windows = QuotaWindow.sort(wins),
                balanceTotal = balance, balanceCurrency = currency,
                error = error,
                raw = result?.toString()
            ))
        }
        if (list.isNotEmpty()) {
            saveAccounts(list)
            val s = root.optJSONObject("settings")
            if (s != null) saveSettings(Settings(
                autoRefresh = s.optBoolean("autoRefresh", true),
                intervalMin = s.optInt("interval", 5).coerceIn(1, 120),
                autoImport = s.optBoolean("autoImport", true)
            ))
        }
        return list.size
    }

    val legacyKey: String get() = LEGACY_KEY

    // ---------------- JSON 序列化 ----------------
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
            error = o.optString("error", "").ifEmpty { null },
            raw = o.optString("raw", "").ifEmpty { null }
        )
    }

    private fun toJson(a: Account): JSONObject = JSONObject().apply {
        put("id", a.id); put("name", a.name); put("provider", a.provider); put("token", a.token)
        put("authMode", a.authMode); put("org", a.org); put("project", a.project); put("planType", a.planType)
        put("lastAt", a.lastAt); put("level", a.level)
        put("balanceTotal", a.balanceTotal); put("balanceCurrency", a.balanceCurrency)
        a.error?.let { put("error", it) }
        a.raw?.let { put("raw", it) }
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
