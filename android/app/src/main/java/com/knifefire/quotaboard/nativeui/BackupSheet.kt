package com.knifefire.quotaboard.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 凭证备份 / 恢复。格式与旧 WebView 版完全一致（QBOARD1: + Base64），
 * 因此旧版导出的备份能直接导入原生版，反之亦然。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSheet(
    mode: String, // "export" | "import"
    accounts: List<Account>,
    repo: Repository,
    onDismiss: () -> Unit,
    onImported: (ImportResult) -> Unit,
    onSelfBackup: (String) -> Unit,
    onNotify: (String) -> Unit
) {
    val ctx = LocalContext.current
    val isExport = mode == "export"
    val exported = remember { if (isExport) repo.exportBackup(accounts) else "" }
    var text by remember { mutableStateOf(exported) }
    var message by remember { mutableStateOf("") }
    var messageOk by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }

    if (isExport) LaunchedEffect(exported) { onSelfBackup(exported) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()
        ) {
            Text(if (isExport) "导出备份" else "导入备份", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(if (isExport) "复制到安全的地方保存" else "粘贴之前保存的备份内容",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))

            Surface(
                color = if (isExport) MaterialTheme.colorScheme.surfaceContainerLow
                else MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (isExport)
                        "这段内容包含你的全部凭证，请存到备忘录、网盘或文件里。切勿发给他人。"
                    else
                        "支持旧版额度看板导出的 QBOARD1: 备份。合并导入会保留现有账号，覆盖导入会替换全部账号。",
                    fontSize = 12.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it; message = "" },
                label = { Text(if (isExport) "备份内容（请完整复制保存）" else "备份内容（粘贴到此处）") },
                readOnly = isExport,
                minLines = 5,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(12.dp))

            if (message.isNotBlank()) {
                Text(message, fontSize = 12.sp,
                    color = if (messageOk) Color(0xFF12B76A) else MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(10.dp))
            }

            if (isExport) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            Platform.copyText(ctx, text)
                            onSelfBackup(text)
                            message = "已复制到剪贴板，去备忘录或微信粘贴保存即可。"
                            messageOk = true
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp)); Text("复制")
                    }
                    OutlinedButton(
                        onClick = {
                            try {
                                Platform.shareText(ctx, text)
                                onSelfBackup(text)
                                message = "已调起分享，可保存到文件或发送到备忘录。"
                                messageOk = true
                            } catch (e: Exception) {
                                message = "分享失败：${e.message}"; messageOk = false
                            }
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp)); Text("分享")
                    }
                }
                if (accounts.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("当前还没有任何账号，导出的是空备份。", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val r = repo.importBackup(text, "merge", accounts)
                            message = r.message; messageOk = r.ok
                            if (r.ok) onImported(r)
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)
                    ) { Text("合并导入") }
                    OutlinedButton(
                        onClick = { confirmReplace = true },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("覆盖导入") }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("覆盖导入") },
            text = { Text("覆盖会删除当前全部 ${accounts.size} 个账号，确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    val r = repo.importBackup(text, "replace", accounts)
                    message = r.message; messageOk = r.ok
                    if (r.ok) onImported(r)
                }) { Text("确认覆盖", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("取消") } }
        )
    }
}
