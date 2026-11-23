package com.knifefire.quotaboard.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 账号详情：额度窗口明细 / 账户余额 / 模型用量 / 币种明细 / 错误原因 / 原始数据 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(acc: Account, onDismiss: () -> Unit, onEdit: () -> Unit) {
    var showRaw by remember { mutableStateOf(false) }
    val isDS = acc.provider == "deepseek"
    val providerLabel = when (acc.provider) {
        "glm-global" -> "Z.AI GLM"
        "deepseek" -> "DeepSeek"
        else -> "GLM Coding Plan"
    }
    val modeTag = if (acc.mode.isNotBlank()) " · ${acc.mode.uppercase()}" else ""

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(acc.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(providerLabel + (if (acc.token.isNotBlank()) " · 凭证已保存$modeTag" else " · 未填写凭证"),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("编辑")
                }
            }
            Spacer(Modifier.height(16.dp))

            when {
                acc.token.isBlank() -> InfoBox("还没有填写凭证。点右上角「编辑」粘贴 API Key 或网页令牌。")
                acc.error != null -> {
                    InfoBox("查询失败：${acc.error}\n\n常见原因：凭证填错 / 复制成了整段 Cookie / 新版积分套餐缺少组织 ID 与项目 ID / 套餐类型选错。", isError = true)
                }
                isDS -> BalanceDetail(acc)
                acc.lastAt <= 0 && acc.windows.isEmpty() -> InfoBox("还没有查询过，点顶部刷新按钮试一下。")
                else -> QuotaDetail(acc)
            }

            if (!acc.raw.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "收起原始数据" else "查看原始数据")
                }
                if (showRaw) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(acc.raw!!, fontSize = 10.sp, lineHeight = 14.sp,
                            modifier = Modifier.padding(12.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun InfoBox(text: String, isError: Boolean = false, warn: Boolean = false) {
    val container = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        warn -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val content = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        warn -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(color = container, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = content,
            modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

/** GLM：额度窗口 + 账户余额 + 余额接口异常提示 */
@Composable
private fun QuotaDetail(acc: Account) {
    val p = acc.primary
    Surface(color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("${p?.remainPct ?: 0}%", fontSize = 40.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("最紧张窗口剩余 · ${p?.label ?: "—"}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(14.dp))
            Grid(
                "套餐等级" to (acc.level.ifBlank { "—" }),
                "额度窗口" to "${acc.windows.size} 个",
                "更新时间" to Platform.fmtTime(acc.lastAt),
                "认证方式" to (acc.mode.ifBlank { acc.authMode }.uppercase())
            )
        }
    }

    if (acc.balanceError.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        InfoBox("余额接口未取到：${acc.balanceError}（不影响额度展示）", warn = true)
    }

    if (!acc.accountBalance.isEmpty) {
        Spacer(Modifier.height(18.dp))
        SectionTitle("账户余额")
        Spacer(Modifier.height(8.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                val b = acc.accountBalance
                Grid(
                    "可用余额" to b.available.orDash(),
                    "账户余额" to b.balance.orDash(),
                    "累计充值" to b.recharge.orDash(),
                    "赠送金额" to b.give.orDash(),
                    "累计消费" to b.spend.orDash(),
                    "冻结金额" to b.frozen.orDash()
                )
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    SectionTitle("额度明细")
    Spacer(Modifier.height(8.dp))
    if (acc.windows.isEmpty()) {
        InfoBox("接口没有返回额度窗口数据。如果用的是新版积分套餐，请在编辑里补上组织 ID、项目 ID 并选择正确的套餐类型。", warn = true)
    } else {
        acc.windows.forEach { w -> WindowDetail(w, w == acc.primary) }
    }
}

@Composable
private fun WindowDetail(w: QuotaWindow, isPrimary: Boolean) {
    val color = when {
        w.remainPct <= 10 -> Color(0xFFF04438)
        w.remainPct <= 30 -> Color(0xFFF79009)
        else -> Color(0xFF12B76A)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.label,
                    fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("剩余 ${w.remainPct}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { w.remainPct / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                drawStopIndicator = {}
            )
            Spacer(Modifier.height(8.dp))
            val reset = listOf(Platform.fmtReset(w.resetAt), Platform.fmtCountdown(w.resetAt))
                .filter { it.isNotBlank() }.joinToString(" · ")
            Row(Modifier.fillMaxWidth()) {
                Text("已用 ${fmtNum(w.used)} / ${fmtNum(w.cap)} · 剩余 ${fmtNum(w.remain)}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Text(reset, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
            if (w.details.isNotEmpty()) {
                Spacer(Modifier.height(9.dp))
                w.details.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(d.modelCode, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        Text(d.usage, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** DeepSeek：总余额 + 赠送/充值 + 逐币种明细 */
@Composable
private fun BalanceDetail(acc: Account) {
    val cur = acc.balanceCurrency
    Surface(color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(Platform.money(acc.balanceTotal, cur), fontSize = 36.sp,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("账户总余额 · " + if (acc.dsAvailable && acc.balanceTotal > 0) "可正常调用 API" else "余额不足",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(14.dp))
            Grid(
                "币种" to cur,
                "赠送余额" to Platform.money(acc.dsGranted, cur),
                "充值余额" to Platform.money(acc.dsTopped, cur),
                "更新时间" to Platform.fmtTime(acc.lastAt)
            )
        }
    }
    Spacer(Modifier.height(18.dp))
    SectionTitle("币种明细")
    Spacer(Modifier.height(8.dp))
    val infos = acc.balanceInfos.ifEmpty {
        listOf(BalanceInfo(cur, acc.balanceTotal, acc.dsGranted, acc.dsTopped))
    }
    infos.forEach { info ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.currency, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                        modifier = Modifier.weight(1f))
                    Text(Platform.money(info.total, info.currency), fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("赠送 ${Platform.money(info.granted, info.currency)}", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("充值 ${Platform.money(info.topped, info.currency)}", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Grid(vararg pairs: Pair<String, String>) {
    Column {
        pairs.toList().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                row.forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(k, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun String.orDash(): String = if (isBlank()) "—" else this

private fun fmtNum(n: Double): String =
    if (n == n.toLong().toDouble()) n.toLong().toString() else String.format("%.2f", n)
