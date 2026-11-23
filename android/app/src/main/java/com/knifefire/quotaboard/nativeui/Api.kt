package com.knifefire.quotaboard.nativeui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 原生网络层：OkHttp，凭证只作为请求头发往官方域名 */
object Api {
    private const val GLM_CN = "https://open.bigmodel.cn"
    private const val GLM_GLOBAL = "https://api.z.ai"
    private const val DEEPSEEK = "https://api.deepseek.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun baseOf(provider: String): String = when (provider) {
        "glm-global" -> GLM_GLOBAL
        "deepseek" -> DEEPSEEK
        else -> GLM_CN
    }

    /** 查询账号额度，返回更新后的 Account（含窗口/余额/错误） */
    suspend fun fetch(acc: Account): Account = withContext(Dispatchers.IO) {
        if (acc.token.isBlank()) return@withContext acc.copy(error = "未配置凭证")
        try {
            if (acc.provider == "deepseek") fetchDeepSeek(acc) else fetchGLM(acc)
        } catch (e: Exception) {
            acc.copy(error = e.message ?: "网络错误", lastAt = System.currentTimeMillis())
        }
    }

    private fun authHeader(acc: Account): String {
        val t = acc.token.trim()
        return if (t.startsWith("Bearer ", ignoreCase = true)) t
        else if (acc.authMode == "raw" || t.startsWith("eyJ")) t
        else "Bearer $t"
    }

    /** 详情页「认证方式」展示实际生效的请求头模式 */
    private fun modeOf(acc: Account): String {
        val t = acc.token.trim()
        return when {
            t.startsWith("Bearer ", ignoreCase = true) -> "BEARER"
            acc.authMode == "raw" -> "RAW"
            t.startsWith("eyJ") -> "RAW"
            else -> "BEARER"
        }
    }

    private fun get(url: String, acc: Account, extra: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url)
            .header("Authorization", authHeader(acc))
            .header("Accept", "application/json")
            .header("User-Agent", "QuotaBoard/2.0")
        extra.forEach { (k, v) -> if (v.isNotBlank()) b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}: ${body.take(120)}")
            return body
        }
    }

    private fun fetchGLM(acc: Account): Account {
        val base = baseOf(acc.provider)
        var url = "$base/api/monitor/usage/quota/limit"
        if (acc.planType.isNotBlank()) url += "?type=${acc.planType}"
        val extra = mapOf(
            "bigmodel-organization" to acc.org,
            "bigmodel-project" to acc.project
        )
        val body = get(url, acc, extra)
        val root = JSONObject(body)
        val code = root.optInt("code", 200)
        if (code != 200) throw Exception(root.optString("msg", "业务错误 $code"))
        val data = root.optJSONObject("data") ?: throw Exception("返回无 data")
        val level = data.optString("level", "—")
        val limits = data.optJSONArray("limits")
        val wins = mutableListOf<QuotaWindow>()
        if (limits != null) for (i in 0 until limits.length()) {
            val l = limits.optJSONObject(i) ?: continue
            wins.add(QuotaWindow.fromJson(l))
        }

        // 账户余额接口：失败不影响额度展示，仅记录原因（与 WebView 版一致）
        var accountBalance = AccountBalance()
        var balanceError = ""
        try {
            val bBody = get("$base/api/biz/account/query-customer-account-report", acc)
            val bRoot = JSONObject(bBody)
            val bCode = bRoot.optInt("code", 200)
            if (bCode != 200 || !bRoot.optBoolean("success", true)) {
                throw Exception(bRoot.optString("msg", "业务错误 $bCode"))
            }
            accountBalance = AccountBalance.fromJson(bRoot)
        } catch (e: Exception) {
            balanceError = e.message ?: "请求失败"
        }

        return acc.copy(
            level = level,
            windows = QuotaWindow.sort(wins),
            error = null,
            raw = body,
            mode = modeOf(acc),
            balanceError = balanceError,
            accountBalance = accountBalance,
            lastAt = System.currentTimeMillis()
        )
    }

    private fun fetchDeepSeek(acc: Account): Account {
        val body = get("$DEEPSEEK/user/balance", acc)
        val root = JSONObject(body)
        if (root.has("error")) throw Exception(root.optJSONObject("error")?.optString("message", "接口错误") ?: "接口错误")
        // 余额接口顶层就是 { is_available, balance_infos }，无 data 包裹；兼容 data 包裹的旧格式
        val data = root.optJSONObject("data") ?: root
        val infos = mutableListOf<BalanceInfo>()
        val arr = data.optJSONArray("balance_infos")
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            infos.add(BalanceInfo.fromJson(o))
        }
        val first = infos.firstOrNull()
        // DeepSeek 不返回额度窗口，用余额总额作为单一指标
        return acc.copy(
            balanceTotal = first?.total ?: 0.0,
            balanceCurrency = first?.currency ?: "CNY",
            balanceInfos = infos,
            dsAvailable = data.optBoolean("is_available", true),
            windows = emptyList(),
            error = null,
            raw = body,
            mode = modeOf(acc),
            lastAt = System.currentTimeMillis()
        )
    }
}
