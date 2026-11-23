package com.knifefire.quotaboard.nativeui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(repo: Repository) {
    val ctx = LocalContext.current
    var accounts by remember { mutableStateOf(repo.loadAccounts()) }
    var settings by remember { mutableStateOf(repo.loadSettings()) }
    var themeMode by remember { mutableStateOf(repo.getThemeMode()) }
    var refreshing by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var backupMode by remember { mutableStateOf<String?>(null) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Account?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingClipboard by remember { mutableStateOf<String?>(null) }
    var dismissedBackup by remember { mutableStateOf("") }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    fun notify(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    fun refreshAll() {
        if (refreshing || accounts.isEmpty()) return
        scope.launch {
            refreshing = true
            val updated = accounts.map { if (it.token.isBlank()) it else Api.fetch(it) }
            accounts = updated
            repo.saveAccounts(updated)
            refreshing = false
        }
    }

    fun refreshOne(id: String) {
        scope.launch {
            refreshing = true
            val updated = accounts.map { if (it.id == id && it.token.isNotBlank()) Api.fetch(it) else it }
            accounts = updated
            repo.saveAccounts(updated)
            refreshing = false
        }
    }

    fun saveAccount(a: Account) {
        val old = accounts.find { it.id == a.id }
        val credChanged = old == null || old.token != a.token || old.provider != a.provider
        val finalA = if (credChanged) a.copy(
            windows = emptyList(), level = "", balanceTotal = 0.0,
            error = null, raw = null, lastAt = 0
        ) else a
        accounts = if (old == null) accounts + finalA
        else accounts.map { if (it.id == a.id) finalA else it }
        repo.saveAccounts(accounts)
        showEditor = false
        detailId = null
        if (finalA.token.isNotBlank()) refreshOne(finalA.id)
    }

    fun deleteAccount(id: String) {
        accounts = accounts.filter { it.id != id }
        repo.saveAccounts(accounts)
        showEditor = false
        detailId = null
        notify("已删除账号")
    }

    // 一次性迁移旧 WebView 版 localStorage 数据
    LaunchedEffect(Unit) {
        val n = LegacyMigration.run(ctx, repo)
        if (n > 0) {
            accounts = repo.loadAccounts()
            settings = repo.loadSettings()
            notify("已从旧版迁移 $n 个账号")
        }
    }

    // 定时自动刷新
    LaunchedEffect(settings.autoRefresh, settings.intervalMin) {
        if (!settings.autoRefresh) return@LaunchedEffect
        while (true) {
            delay(settings.intervalMin * 60_000L)
            if (!refreshing && accounts.isNotEmpty()) refreshAll()
        }
    }

    // 回前台：剪贴板自动导入 + 超时自动刷新
    val onResumeNow by rememberUpdatedState {
        val s = repo.loadSettings()
        if (s.autoImport) {
            val t = Platform.readClipboard(ctx)
            if (t.startsWith(Repository.BACKUP_PREFIX) && t != dismissedBackup) {
                val n = repo.peekBackupCount(t)
                if (n > 0) {
                    if (accounts.isEmpty()) {
                        val r = repo.importBackup(t, "replace", accounts)
                        if (r.ok) {
                            dismissedBackup = t
                            accounts = repo.loadAccounts()
                            notify("已从剪贴板自动恢复 $n 个账号")
                            refreshAll()
                        }
                    } else {
                        pendingClipboard = t
                    }
                }
            }
        }
        val last = accounts.maxOfOrNull { it.lastAt } ?: 0
        if (s.autoRefresh && System.currentTimeMillis() - last > 60_000 && accounts.isNotEmpty()) {
            refreshAll()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResumeNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BoardTheme(themeMode) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text("额度看板", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = { showThemeSheet = true }) {
                            Icon(Icons.Default.Palette, contentDescription = "主题")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                        IconButton(onClick = { refreshAll() }, enabled = !refreshing) {
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
                    onClick = { editing = null; showEditor = true },
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
                        Text("旧版数据会自动迁移，也可用设置里的导入备份", fontSize = 12.sp,
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
                        val cardHeightPx = 260f
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
                                                if (target != index) {
                                                    accounts = accounts.toMutableList().apply { add(target, removeAt(index)) }
                                                    draggingIndex = target
                                                    repo.saveAccounts(accounts)
                                                }
                                                accumulatedY = 0f
                                            }
                                        },
                                        onDragEnd = { draggingIndex = -1; accumulatedY = 0f },
                                        onDragCancel = { draggingIndex = -1; accumulatedY = 0f }
                                    )
                                }
                        ) {
                            AccountCardContent(
                                acc = acc,
                                dragging = isDragging,
                                onDetail = { detailId = acc.id },
                                onEdit = { editing = acc; showEditor = true }
                            )
                        }
                    }
                }
            }
        }

        // 详情弹层
        detailId?.let { id ->
            accounts.find { it.id == id }?.let { acc ->
                DetailSheet(
                    acc = acc,
                    onDismiss = { detailId = null },
                    onEdit = { editing = acc; detailId = null; showEditor = true }
                )
            }
        }

        // 新增 / 编辑弹层
        if (showEditor) {
            AccountEditorSheet(
                initial = editing,
                onDismiss = { showEditor = false },
                onSave = { saveAccount(it) },
                onDelete = if (editing != null) { { id: String -> deleteAccount(id) } } else null
            )
        }

        // 主题弹层
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

        // 设置弹层
        if (showSettings) {
            SettingsSheet(
                settings = settings,
                themeMode = themeMode,
                onSettingsChange = { settings = it; repo.saveSettings(it) },
                onThemeClick = { showSettings = false; showThemeSheet = true },
                onExport = { showSettings = false; backupMode = "export" },
                onImport = { showSettings = false; backupMode = "import" },
                onClearAll = {
                    accounts = emptyList()
                    repo.saveAccounts(emptyList())
                    showSettings = false
                    detailId = null
                    notify("已清空全部数据")
                },
                onDismiss = { showSettings = false }
            )
        }

        // 备份 / 恢复弹层
        backupMode?.let { mode ->
            BackupSheet(
                mode = mode,
                accounts = accounts,
                repo = repo,
                onDismiss = { backupMode = null },
                onImported = { r ->
                    accounts = repo.loadAccounts()
                    backupMode = null
                    notify(r.message)
                    refreshAll()
                },
                onSelfBackup = { dismissedBackup = it },
                onNotify = { notify(it) }
            )
        }

        // 剪贴板检测到备份（已有账号时询问）
        pendingClipboard?.let { t ->
            val n = repo.peekBackupCount(t)
            AlertDialog(
                onDismissRequest = { pendingClipboard = null; dismissedBackup = t },
                title = { Text("发现凭证备份") },
                text = { Text("检测到剪贴板中有凭证备份（$n 个账号）。\n\n合并导入会保留现有账号，忽略则不再提示这一份。") },
                confirmButton = {
                    TextButton(onClick = {
                        val r = repo.importBackup(t, "merge", accounts)
                        dismissedBackup = t
                        pendingClipboard = null
                        if (r.ok) {
                            accounts = repo.loadAccounts()
                            notify(r.message)
                            refreshAll()
                        } else notify(r.message)
                    }) { Text("合并导入") }
                },
                dismissButton = {
                    TextButton(onClick = { dismissedBackup = t; pendingClipboard = null }) { Text("忽略") }
                }
            )
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
private fun AccountCardContent(
    acc: Account,
    dragging: Boolean,
    onDetail: () -> Unit,
    onEdit: () -> Unit
) {
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
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (acc.lastAt > 0) "更新于 ${Platform.fmtTime(acc.lastAt)}" else "尚未更新",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("编辑", fontSize = 12.sp)
            }
            TextButton(onClick = onDetail, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("详情 ›", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatusBadge(acc: Account) {
    val isDS = acc.provider == "deepseek"
    val danger = acc.error != null || (!isDS && acc.primary?.remainPct == 0) ||
            (isDS && acc.balanceTotal <= 0 && acc.lastAt > 0)
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
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            drawStopIndicator = {}
        )
        Spacer(Modifier.width(10.dp))
        Text("${w.remainPct}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color,
            modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
    }
}
