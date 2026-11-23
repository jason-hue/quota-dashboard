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

/** 账号详情：额度窗口明细 / 余额 / 错误原因 / 原始数据 */
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(acc.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(providerLabel + if (acc.token.isNotBlank()) " · 凭证已保存" else " · 未填写凭证",
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
                acc.windows.isEmpty() -> InfoBox("还没有查询过，点顶部刷新按钮试一下。")
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
private fun InfoBox(text: String, isError: Boolean = false) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, fontSize = 13.sp, lineHeight = 19.sp,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun QuotaDetail(acc: Account) {
    val p = acc.primary
    Surface(color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("${p?.remainPct ?: 0}%", fontSize = 40.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("最紧张窗口剩余 · ${p?.short ?: "—"}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(14.dp))
            Grid(
                "套餐等级" to (acc.level.ifBlank { "—" }),
                "额度窗口" to "${acc.windows.size} 个",
                "更新时间" to Platform.fmtTime(acc.lastAt),
                "认证方式" to acc.authMode.uppercase()
            )
        }
    }
    Spacer(Modifier.height(18.dp))
    Text("额度明细", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    acc.windows.forEach { w -> WindowDetail(w, w == acc.primary) }
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
                Text(if (isPrimary) "${w.short} · 最紧" else w.short,
                    fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.weight(1f))
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
            Row {
                Text("已用 ${fmtNum(w.used)} / ${fmtNum(w.cap)}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                val reset = Platform.fmtReset(w.resetAt)
                if (reset.isNotBlank()) Text("重置 $reset", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun BalanceDetail(acc: Account) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("¥${String.format("%.2f", acc.balanceTotal)}", fontSize = 36.sp,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("账户总余额", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(14.dp))
            Grid(
                "币种" to acc.balanceCurrency,
                "更新时间" to Platform.fmtTime(acc.lastAt),
                "状态" to if (acc.balanceTotal > 0) "可正常调用 API" else "余额不足"
            )
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

private fun fmtNum(n: Double): String =
    if (n == n.toLong().toDouble()) n.toLong().toString() else String.format("%.2f", n)
