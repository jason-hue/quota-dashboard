package com.knifefire.quotaboard.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

/** 添加账号弹层：手动填写凭证。一键登录抓取在第二阶段接入。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountSheet(onDismiss: () -> Unit, onAdd: (Account) -> Unit) {
    var name by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("glm-cn") }
    var token by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var planType by remember { mutableStateOf("") }

    val providers = listOf(
        "glm-cn" to "GLM Coding Plan（国内）",
        "glm-global" to "Z.AI GLM（国际）",
        "deepseek" to "DeepSeek"
    )
    val showAdvanced = provider != "deepseek"

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()
        ) {
            Text("添加账号", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("凭证只保存在本机", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            Text("平台", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            providers.forEach { (key, label) ->
                SelectableRow(label, selected = provider == key) { provider = key }
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

            Button(
                onClick = {
                    val finalName = name.ifBlank { providers.first { it.first == provider }.second }
                    onAdd(Account(
                        id = UUID.randomUUID().toString(),
                        name = finalName,
                        provider = provider,
                        token = token.trim(),
                        org = org.trim(),
                        project = project.trim(),
                        planType = planType.trim()
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
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerLow
    Surface(onClick = onClick, color = color, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 14.sp)
            if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
