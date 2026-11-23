package com.knifefire.quotaboard.nativeui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knifefire.quotaboard.LoginActivity
import java.util.UUID

/**
 * 新增 / 编辑账号弹层。
 * 一键登录直接复用旧版 LoginActivity（独立 Activity，抓到凭证后 setResult 回传），
 * 不依赖 Capacitor 插件，原生页也能用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditorSheet(
    initial: Account?,
    onDismiss: () -> Unit,
    onSave: (Account) -> Unit,
    onDelete: ((String) -> Unit)? = null
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var provider by remember { mutableStateOf(initial?.provider ?: "glm-cn") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    var authMode by remember { mutableStateOf(initial?.authMode ?: "auto") }
    var org by remember { mutableStateOf(initial?.org ?: "") }
    var project by remember { mutableStateOf(initial?.project ?: "") }
    var planType by remember { mutableStateOf(initial?.planType ?: "") }
    var loginStatus by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val providers = listOf(
        "glm-cn" to "GLM Coding Plan（国内）",
        "glm-global" to "Z.AI GLM（国际）",
        "deepseek" to "DeepSeek"
    )
    val showAdvanced = provider != "deepseek"

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val d = result.data
        val t = d?.getStringExtra(LoginActivity.RESULT_TOKEN).orEmpty()
        if (result.resultCode == Activity.RESULT_OK && t.isNotBlank()) {
            token = t
            authMode = d?.getStringExtra(LoginActivity.RESULT_AUTHMODE).orEmpty().ifBlank { "auto" }
            org = d?.getStringExtra(LoginActivity.RESULT_ORG).orEmpty()
            project = d?.getStringExtra(LoginActivity.RESULT_PROJECT).orEmpty()
            planType = d?.getStringExtra(LoginActivity.RESULT_PLANTYPE).orEmpty()
            loginStatus = "凭证已自动填入" +
                    (if (org.isNotBlank() || project.isNotBlank()) "（含组织/项目 ID）" else "") +
                    "，点保存即可。"
        } else {
            loginStatus = if (result.resultCode == Activity.RESULT_OK)
                "没有读取到凭证。请确认已完成登录，或改用手动粘贴。"
            else "已取消登录。"
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()
        ) {
            Text(if (initial == null) "添加账号" else "编辑账号", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("凭证只保存在本机，不会上传", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            Text("平台", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            providers.forEach { (key, label) ->
                SelectableRow(label, selected = provider == key) {
                    provider = key; loginStatus = ""
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(name, { name = it }, label = { Text("备注名称") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(token, { token = it },
                label = { Text(if (provider == "deepseek") "API Key（sk-...）" else "API Key 或 Bearer Token") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    loginStatus = "正在打开登录页，请在弹出的页面中登录…"
                    loginLauncher.launch(
                        Intent(ctx, LoginActivity::class.java)
                            .putExtra(LoginActivity.EXTRA_PROVIDER, provider)
                    )
                },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Login, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("一键登录自动获取凭证")
            }
            if (loginStatus.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(loginStatus, fontSize = 12.sp,
                    color = if (loginStatus.contains("已自动填入")) colorOk()
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))

            Text("认证方式", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "自动", "bearer" to "Bearer", "raw" to "原始").forEach { (k, label) ->
                    Surface(
                        onClick = { authMode = k },
                        color = if (authMode == k) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (showAdvanced) {
                Text("新版积分套餐可填（可选）", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(org, { org = it }, label = { Text("组织 ID") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(project, { project = it }, label = { Text("项目 ID") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(planType, { planType = it }, label = { Text("套餐类型（URL type 参数）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
            }

            Text(howto(provider), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    val finalName = name.ifBlank { providers.first { it.first == provider }.second }
                    onSave(Account(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        name = finalName,
                        provider = provider,
                        token = token.trim(),
                        authMode = authMode,
                        org = org.trim(),
                        project = project.trim(),
                        planType = planType.trim(),
                        // 额度数据由 HomeScreen 按是否改凭证决定保留还是清空
                        lastAt = initial?.lastAt ?: 0,
                        level = initial?.level ?: "",
                        windows = initial?.windows ?: emptyList(),
                        balanceTotal = initial?.balanceTotal ?: 0.0,
                        balanceCurrency = initial?.balanceCurrency ?: "CNY",
                        error = initial?.error,
                        raw = initial?.raw
                    ))
                },
                enabled = token.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("保存")
            }

            if (onDelete != null && initial != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("删除这个账号")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showDeleteConfirm && initial != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除账号") },
            text = { Text("确定删除「${initial.name}」及其凭证吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete?.invoke(initial.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

private fun colorOk() = Color(0xFF12B76A)

private fun howto(provider: String): String = when (provider) {
    "deepseek" -> "获取凭证：在 platform.deepseek.com 创建 API Key（sk- 开头），粘贴到这里即可。查询余额只需要这一个 Key。"
    "glm-global" -> "获取凭证：在 z.ai 登录后按 F12 → Network → 打开用量页 → 找到 quota/limit 请求 → 复制请求头里的 Authorization；或直接用 Z.AI 的 API Key。不要复制整段 Cookie。"
    else -> "获取凭证：API Key 在 bigmodel.cn 控制台创建；网页令牌在登录后按 F12 → Network → 刷新用量页 → 找到 quota/limit 请求 → 复制 Authorization。新版积分套餐还需组织 ID、项目 ID 和套餐类型。"
}

@Composable
private fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerLow
    Surface(onClick = onClick, color = color, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 14.sp)
            if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
