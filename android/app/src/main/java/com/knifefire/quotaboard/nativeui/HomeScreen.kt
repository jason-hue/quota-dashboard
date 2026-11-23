package com.knifefire.quotaboard.nativeui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(repo: Repository) {
    var accounts by remember { mutableStateOf(repo.loadAccounts()) }
    var themeMode by remember { mutableStateOf(repo.getThemeMode()) }
    var refreshing by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun persist() = repo.saveAccounts(accounts)

    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            accounts = accounts.map { Api.fetch(it) }
            persist()
            refreshing = false
        }
    }

    // 长按拖拽排序：超过阈值就与相邻项交换，带动画
    fun moveItem(from: Int, to: Int) {
        if (from == to || to < 0 || to >= accounts.size) return
        accounts = accounts.toMutableList().apply { add(to, removeAt(from)) }
        draggingIndex = to
        persist()
    }

    BoardTheme(themeMode) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("额度看板", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = { showThemeSheet = true }) {
                            Icon(Icons.Default.Palette, contentDescription = "主题")
                        }
                        IconButton(onClick = { refresh() }, enabled = !refreshing) {
                            if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showAddSheet = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("添加账号") }
                )
            }
        ) { padding ->
            if (accounts.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Dashboard, null, Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("还没有账号", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text("点右下角 ＋ 添加 GLM 或 DeepSeek", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(accounts, key = { _, a -> a.id }) { index, acc ->
                        val isDragging = draggingIndex == index
                        var accumulatedY by remember { mutableFloatStateOf(0f) }
                        val cardHeightPx = 220f
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDragging)
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                else MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (isDragging) 12.dp else 1.dp
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItemPlacement()
                                .pointerInput(accounts.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggingIndex = index; accumulatedY = 0f },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            accumulatedY += dragAmount.y
                                            val steps = (accumulatedY / cardHeightPx).roundToInt()
                                            if (steps != 0) {
                                                val target = (index + steps).coerceIn(0, accounts.size - 1)
                                                moveItem(index, target)
                                                accumulatedY = 0f
                                            }
                                        },
                                        onDragEnd = { draggingIndex = -1; accumulatedY = 0f },
                                        onDragCancel = { draggingIndex = -1; accumulatedY = 0f }
                                    )
                                }
                        ) {
                            AccountCardContent(acc, isDragging)
                        }
                    }
                }
            }
        }

        if (showThemeSheet) {
            ModalBottomSheet(onDismissRequest = { showThemeSheet = false }) {
                Column(Modifier.padding(16.dp).navigationBarsPadding()) {
                    Text("主题配色", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    ThemeOption("brand", "蓝紫品牌色", "固定配色，跨机型一致", themeMode) {
                        themeMode = it; repo.setThemeMode(it); showThemeSheet = false
                    }
                    ThemeOption("dynamic", "跟随系统取色", "从壁纸提取颜色（Android 12+）", themeMode) {
                        themeMode = it; repo.setThemeMode(it); showThemeSheet = false
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        if (showAddSheet) {
            AddAccountSheet(onDismiss = { showAddSheet = false }, onAdd = { newAcc ->
                accounts = accounts + newAcc
                persist()
                showAddSheet = false
                // 添加后立即查询该账号额度
                scope.launch {
                    refreshing = true
                    accounts = accounts.map { if (it.id == newAcc.id) Api.fetch(it) else it }
                    persist()
                    refreshing = false
                }
            })
        }
    }
}

@Composable
private fun ThemeOption(key: String, title: String, sub: String, current: String, onClick: (String) -> Unit) {
    val selected = current == key
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow
    )
    Surface(onClick = { onClick(key) }, color = bg, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AccountCardContent(acc: Account, dragging: Boolean) {
    val isDS = acc.provider == "deepseek"
    val providerLabel = when (acc.provider) {
        "glm-global" -> "Z.AI GLM"
        "deepseek" -> "DeepSeek"
        else -> "GLM Coding Plan"
    }
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DragIndicator, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(acc.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                val plan = if (!isDS && acc.level.isNotBlank() && acc.level != "—") " · 套餐 ${acc.level}" else ""
                Text(providerLabel + plan, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusBadge(acc)
        }
        Spacer(Modifier.height(14.dp))
        if (acc.error != null) {
            Text(acc.error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        } else if (isDS) {
            BalanceRow(acc)
        } else if (acc.windows.isEmpty()) {
            Text("尚无额度数据，点右上角刷新", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            acc.windows.forEach { w -> WindowRow(w, w == acc.primary) }
        }
    }
}

@Composable
private fun StatusBadge(acc: Account) {
    val isDS = acc.provider == "deepseek"
    val danger = acc.error != null || (!isDS && acc.primary?.remainPct == 0)
    val warn = !danger && !isDS && (acc.primary?.remainPct ?: 100) <= 20
    val (label, color) = when {
        danger -> "额度告急" to MaterialTheme.colorScheme.error
        warn -> "偏低" to Color(0xFFF79009)
        else -> "正常" to Color(0xFF12B76A)
    }
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun BalanceRow(acc: Account) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("可用余额", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text("¥${String.format("%.2f", acc.balanceTotal)}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WindowRow(w: QuotaWindow, isPrimary: Boolean) {
    val color = when {
        w.remainPct <= 10 -> Color(0xFFF04438)
        w.remainPct <= 30 -> Color(0xFFF79009)
        else -> Color(0xFF12B76A)
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (isPrimary) "${w.short} · 最紧" else w.short, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        LinearProgressIndicator(
            progress = { w.remainPct / 100f },
            modifier = Modifier.weight(1f).height(8.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        Spacer(Modifier.width(10.dp))
        Text("${w.remainPct}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color,
            modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
