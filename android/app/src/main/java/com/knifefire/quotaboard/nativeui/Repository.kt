package com.knifefire.quotaboard.nativeui

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 窗口内按模型拆分的用量明细（对齐 WebView 版 usageDetails） */
data class UsageDetail(val modelCode: String, val usage: String)

/** DeepSeek 按币种拆分的余额明细（对齐 WebView 版 balance_infos） */
data class BalanceInfo(
    val currency: String,
    val total: Double,
    val granted: Double,
    val topped: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("currency", currency); put("total", total)
        put("granted", granted); put("topped", topped)
    }

    companion object {
        fun fromJson(o: JSONObject): BalanceInfo = BalanceInfo(
            currency = o.optString("currency", "CNY"),
            total = o.optDouble("total_balance", 0.0),
            granted = o.optDouble("granted_balance", 0.0),
            topped = o.optDouble("topped_up_balance", 0.0)
        )
        fun fromRaw(o: JSONObject): BalanceInfo = BalanceInfo(
            currency = o.optString("currency", "CNY"),
            total = o.optDouble("total", 0.0),
            granted = o.optDouble("granted", 0.0),
            topped = o.optDouble("topped", 0.0)
        )
    }
}

/** GLM 账户余额（6 项，保留服务端原值，缺失显示 —） */
data class AccountBalance(
    val available: String = "",
    val balance: String = "",
    val recharge: String = "",
    val give: String = "",
    val spend: String = "",
    val frozen: String = ""
) {
    val isEmpty: Boolean get() = listOf(available, balance, recharge, give, spend, frozen).all { it.isBlank() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("availableBalance", available); put("balance", balance)
        put("rechargeAmount", recharge); put("giveAmount", give)
        put("totalSpendAmount", spend); put("frozenBalance", frozen)
    }

    companion object {
        fun fromJson(o: JSONObject?): AccountBalance {
            if (o == null) return AccountBalance()
            fun t(k: String): String = if (o.has(k) && !o.isNull(k)) o.optString(k, "") else ""
            // 服务端可能把数值放在 data 里，也可能直接平铺
            val b = o.optJSONObject("data") ?: o
            fun u(k: String): String = if (b.has(k) && !b.isNull(k)) b.optString(k, "") else ""
            return AccountBalance(
                available = u("availableBalance").ifBlank { t("availableBalance") },
                balance = u("balance").ifBlank { t("balance") },
                recharge = u("rechargeAmount").ifBlank { t("rechargeAmount") },
                give = u("giveAmount").ifBlank { t("giveAmount") },
                spend = u("totalSpendAmount").ifBlank { t("totalSpendAmount") },
                frozen = u("frozenBalance").ifBlank { t("frozenBalance") }
            )
        }
    }
}

/** 单个额度窗口（字段对齐 WebView 版 parseWindow） */
data class QuotaWindow(
    val short: String,
    val unit: Int,
    val remainPct: Int,
    val usedPct: Int,
    val cap: Double,
    val used: Double,
    val resetAt: String?,
    val title: String = "",
    val number: Int = 0,
    val remain: Double = 0.0,
    val details: List<UsageDetail> = emptyList()
) {
    val label: String get() = title.ifBlank { short }

    fun toJson(): JSONObject = JSONObject().apply {
        put("short", short); put("unit", unit); put("remainPct", remainPct)
        put("usedPct", usedPct); put("cap", cap); put("used", used)
        resetAt?.let { put("resetAt", it) }
        put("title", title); put("number", number); put("remain", remain)
        val da = JSONArray()
        details.forEach { d -> da.put(JSONObject().apply { put("modelCode", d.modelCode); put("usage", d.usage) }) }
        put("details", da)
    }

    companion object {
        /** 与旧 WebView 版 parseWindow 的短标签规则保持一致 */
        fun shortOf(unit: Int, number: Int): String = when (unit) {
            6 -> "本周"
            3 -> "${if (number > 0) number else 5}小时"
            5 -> "本月"
            1 -> if (number > 1) "${number}天" else "每天"
            else -> "${if (number > 1) number else ""}${unitName(unit)}"
        }
        fun unitName(u: Int) = when (u) {
            3 -> "小时"; 6 -> "周"; 5 -> "月"; 1 -> "天"; else -> "单位$u"
        }

        /** 详情里用的完整标题，如「Token 池 · 5小时窗口」 */
        fun titleOf(type: String, unit: Int, number: Int): String {
            val kind = if (type == "TOKENS_LIMIT") "Token 池" else "调用池"
            val n = if (number > 1) number.toString() else ""
            return "$kind · $n${unitName(unit)}窗口"
        }

        private val RANK = mapOf(3 to 0, 1 to 1, 6 to 2, 5 to 3)
        fun sort(wins: List<QuotaWindow>): List<QuotaWindow> =
            wins.sortedBy { RANK[it.unit] ?: 9 }

        /** 从服务端 limits[] 单项解析出完整窗口数据 */
        fun fromJson(l: JSONObject): QuotaWindow {
            val usedPct = l.optDouble("percentage", 0.0).toInt()
            val unit = l.optInt("unit", 0)
            val number = l.optInt("number", 0)
            val cap = if (l.has("usage") && !l.isNull("usage")) l.optDouble("usage", 0.0)
            else l.optDouble("currentValue", 0.0)
            val used = l.optDouble("currentValue", 0.0)
            val remain = if (l.has("remaining") && !l.isNull("remaining")) l.optDouble("remaining", 0.0)
            else maxOf(0.0, cap - used)
            val details = mutableListOf<UsageDetail>()
            val da = l.optJSONArray("usageDetails")
            if (da != null) for (i in 0 until da.length()) {
                val d = da.optJSONObject(i) ?: continue
                val code = if (d.has("modelCode") && !d.isNull("modelCode")) d.optString("modelCode", "") else ""
                val usage = if (d.has("usage") && !d.isNull("usage")) d.optString("usage", "") else ""
                details.add(UsageDetail(code, usage))
            }
            return QuotaWindow(
                short = shortOf(unit, number),
                unit = unit,
                remainPct = (100 - usedPct).coerceIn(0, 100),
                usedPct = usedPct,
                cap = cap,
                used = used,
                resetAt = l.optString("nextResetTime", "").ifEmpty { null },
                title = titleOf(l.optString("type", ""), unit, number),
                number = number,
                remain = remain,
                details = details
            )
        }

        fun fromRaw(o: JSONObject): QuotaWindow {
            val details = mutableListOf<UsageDetail>()
            val da = o.optJSONArray("details")
            if (da != null) for (i in 0 until da.length()) {
                val d = da.optJSONObject(i) ?: continue
                details.add(UsageDetail(d.optString("modelCode", ""), d.optString("usage", "")))
            }
            return QuotaWindow(
                short = o.optString("short", ""),
                unit = o.optInt("unit", 0),
                remainPct = o.optInt("remainPct", 0),
                usedPct = o.optInt("usedPct", 0),
                cap = o.optDouble("cap", 0.0),
                used = o.optDouble("used", 0.0),
                resetAt = o.optString("resetAt", "").ifEmpty { null },
                title = o.optString("title", ""),
                number = o.optInt("number", 0),
                remain = o.optDouble("remain", 0.0),
                details = details
            )
        }
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
    val raw: String? = null,
    val mode: String = "",
    val balanceError: String = "",
    val accountBalance: AccountBalance = AccountBalance(),
    val dsAvailable: Boolean = true,
    val balanceInfos: List<BalanceInfo> = emptyList()
) {
    /** 最紧张窗口（剩余百分比最低） */
    val primary: QuotaWindow? get() = windows.minByOrNull { it.remainPct }
    val dsGranted: Double get() = balanceInfos.firstOrNull()?.granted ?: 0.0
    val dsTopped: Double get() = balanceInfos.firstOrNull()?.topped ?: 0.0
}

data class Settings(
    val autoRefresh: Boolean = true,
    val intervalMin: Int = 5,
    val autoImport: Boolean = true
)

data class ImportResult(val ok: Boolean, val message: String, val count: Int = 0)

private fun parseInfos(arr: JSONArray?): List<BalanceInfo> {
    if (arr == null) return emptyList()
    val out = mutableListOf<BalanceInfo>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(BalanceInfo.fromJson(o))
    }
    return out
}

private fun parseInfosRaw(arr: JSONArray?): List<BalanceInfo> {
    if (arr == null) return emptyList()
    val out = mutableListOf<BalanceInfo>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(BalanceInfo.fromRaw(o))
    }
    return out
}

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
     * 不仅迁移凭证，还把已缓存的额度窗口/余额/模型明细一起带过来，避免切换入口后详情变空。
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
            var mode = ""
            var balanceError = ""
            var accountBalance = AccountBalance()
            var dsAvailable = true
            var infos = emptyList<BalanceInfo>()
            if (result != null) {
                mode = result.optString("mode", "")
                balanceError = result.optString("balanceError", "")
                accountBalance = AccountBalance.fromJson(result.optJSONObject("balance"))
                if (provider == "deepseek") {
                    val dsRoot = result.optJSONObject("data") ?: result
                    infos = parseInfos(dsRoot.optJSONArray("balance_infos"))
                    balance = infos.firstOrNull()?.total ?: 0.0
                    currency = infos.firstOrNull()?.currency ?: "CNY"
                    dsAvailable = dsRoot.optBoolean("is_available", true)
                }
                if (result.optBoolean("ok", false)) {
                    if (provider != "deepseek") {
                        val d = result.optJSONObject("quota")?.optJSONObject("data")
                        level = d?.optString("level", "") ?: ""
                        val limits = d?.optJSONArray("limits")
                        if (limits != null) for (j in 0 until limits.length()) {
                            val l = limits.optJSONObject(j) ?: continue
                            wins.add(QuotaWindow.fromJson(l))
                        }
                    }
                } else {
                    error = result.optString("error", "查询失败").ifBlank { "查询失败" }
                }
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
                raw = result?.toString(),
                mode = mode,
                balanceError = balanceError,
                accountBalance = accountBalance,
                dsAvailable = dsAvailable,
                balanceInfos = infos
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
            wins.add(QuotaWindow.fromRaw(wa.getJSONObject(i)))
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
            raw = o.optString("raw", "").ifEmpty { null },
            mode = o.optString("mode", ""),
            balanceError = o.optString("balanceError", ""),
            accountBalance = AccountBalance.fromJson(o.optJSONObject("accountBalance")),
            dsAvailable = o.optBoolean("dsAvailable", true),
            balanceInfos = parseInfosRaw(o.optJSONArray("balanceInfos"))
        )
    }

    private fun toJson(a: Account): JSONObject = JSONObject().apply {
        put("id", a.id); put("name", a.name); put("provider", a.provider); put("token", a.token)
        put("authMode", a.authMode); put("org", a.org); put("project", a.project); put("planType", a.planType)
        put("lastAt", a.lastAt); put("level", a.level)
        put("balanceTotal", a.balanceTotal); put("balanceCurrency", a.balanceCurrency)
        put("mode", a.mode); put("balanceError", a.balanceError)
        put("accountBalance", a.accountBalance.toJson())
        put("dsAvailable", a.dsAvailable)
        a.error?.let { put("error", it) }
        a.raw?.let { put("raw", it) }
        val arr = JSONArray()
        a.windows.forEach { w -> arr.put(w.toJson()) }
        put("windows", arr)
        val ia = JSONArray()
        a.balanceInfos.forEach { info -> ia.put(info.toJson()) }
        put("balanceInfos", ia)
    }
}
