package com.knifefire.quotaboard.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    themeMode: String,
    onSettingsChange: (Settings) -> Unit,
    onThemeClick: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()
        ) {
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))

            SectionTitle("刷新")
            SettingSwitch("自动刷新", "按下方间隔定时更新额度", settings.autoRefresh) {
                onSettingsChange(settings.copy(autoRefresh = it))
            }
            Spacer(Modifier.height(8.dp))
            Text("刷新间隔", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10, 30).forEach { m ->
                    val selected = settings.intervalMin == m
                    Surface(
                        onClick = { onSettingsChange(settings.copy(intervalMin = m)) },
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("${m}分", fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            SectionTitle("外观")
            SettingRow("主题配色", if (themeMode == "dynamic") "跟随系统取色" else "蓝紫品牌色",
                Icons.Default.Palette, onThemeClick)
            Spacer(Modifier.height(18.dp))

            SectionTitle("数据")
            SettingSwitch("剪贴板自动导入", "回前台时检测剪贴板里的备份并提示恢复", settings.autoImport) {
                onSettingsChange(settings.copy(autoImport = it))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.FileUpload, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("导出备份")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.FileDownload, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("导入备份")
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("清空全部数据")
            }
            Spacer(Modifier.height(12.dp))
            Text("额度看板 · 原生版\n凭证仅保存在本机，不会上传到任何服务器。",
                fontSize = 11.sp, lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部数据") },
            text = { Text("建议先「导出备份」保存凭证！清空后无法恢复。\n\n确定要清空全部账号吗？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                }) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingSwitch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingRow(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                       onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(title, Modifier.weight(1f), fontSize = 14.sp)
            Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
