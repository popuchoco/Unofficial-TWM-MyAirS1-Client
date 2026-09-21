package com.kerberosclaw.myairs1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter

private enum class AppPage(val label: String, val icon: ImageVector) {
    OVERVIEW("總覽", Icons.Outlined.Home), CONNECTION("連線", Icons.Outlined.Bluetooth),
    MEASUREMENT("量測", Icons.Outlined.Air), REPORT("報告", Icons.AutoMirrored.Outlined.ShowChart),
    DEVICE("裝置", Icons.Outlined.Devices)
}

private data class ChartDatum(val label: String, val value: Double)

class MainActivity : ComponentActivity() {
    private val app get() = application as MyAirApplication
    private val db get() = app.database
    private val ble get() = app.bleManager
    private val coordinator get() = app.measurementCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themeMode by remember { mutableStateOf(SettingsStore.theme(this)) }
            val dark = when (themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
            MyAirTheme(dark) {
                MainApp(themeMode) { mode -> themeMode = mode; SettingsStore.setTheme(this, mode) }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun MainApp(themeMode: ThemeMode, onThemeChanged: (ThemeMode) -> Unit) {
        val state by ble.state.collectAsState()
        val queueState by coordinator.state.collectAsState()
        var page by remember { mutableStateOf(AppPage.OVERVIEW) }
        var granted by remember { mutableStateOf(hasPermissions()) }
        var disconnectAlert by remember { mutableStateOf(SettingsStore.disconnectAlert(this)) }
        var storedLatest by remember { mutableStateOf(db.latestReportPoint()) }
        var todayPoints by remember { mutableStateOf(emptyList<ReportPoint>()) }
        var monthPoints by remember { mutableStateOf(emptyList<ReportPoint>()) }
        var exportChoice by remember { mutableStateOf<Boolean?>(null) }
        var showExportDialog by remember { mutableStateOf(false) }
        var showScheduleDialog by remember { mutableStateOf(false) }
        var localSchedule by remember { mutableStateOf(db.localSchedule()) }
        var customDelay by remember { mutableStateOf("") }
        var dailyTime by remember { mutableStateOf("08:00") }

        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPermissions() }
        val diagnosticExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer -> writer.write(db.exportJson()) } }
        }
        val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val monthly = exportChoice
            if (uri != null && monthly != null) contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write("\uFEFF"); it.write(csv(monthly))
            }
            exportChoice = null
        }

        LaunchedEffect(state.latestSession, page) {
            storedLatest = db.latestReportPoint()
            if (page == AppPage.REPORT) {
                val bounds = reportBounds()
                todayPoints = db.reportPoints(bounds.first, bounds.second)
                monthPoints = db.reportPoints(bounds.third, bounds.second)
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                localSchedule = db.localSchedule()
                delay(5_000)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("MYAIR S1", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(page.label, style = MaterialTheme.typography.titleLarge)
                    }
                })
            },
            bottomBar = {
                NavigationBar { AppPage.entries.forEach { item ->
                    NavigationBarItem(
                        selected = page == item,
                        onClick = { page = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                } }
            }
        ) { padding ->
            when (page) {
                AppPage.OVERVIEW -> OverviewPage(Modifier.padding(padding), state, storedLatest)
                AppPage.CONNECTION -> ConnectionPage(Modifier.padding(padding), state, granted, disconnectAlert,
                    requestPermissions = { permissions.launch(requiredPermissions()) },
                    onDisconnectAlert = { disconnectAlert = it; SettingsStore.setDisconnectAlert(this, it) })
                AppPage.MEASUREMENT -> MeasurementPage(Modifier.padding(padding), state, queueState, localSchedule,
                    openSchedule = { showScheduleDialog = true },
                    cancelSchedule = { db.cancelSchedule(); localSchedule = null },
                    exportDiagnostic = { diagnosticExport.launch("myair-s1-diagnostic.json") })
                AppPage.REPORT -> ReportPage(Modifier.padding(padding), todayPoints, monthPoints) { showExportDialog = true }
                AppPage.DEVICE -> DevicePage(Modifier.padding(padding), state, themeMode, onThemeChanged)
            }
        }
        if (showExportDialog) AlertDialog(
            onDismissRequest = { showExportDialog = false },
            icon = { Icon(Icons.Outlined.TableView, contentDescription = null) }, title = { Text("匯出量測資料") },
            text = { Text("請選擇 CSV 的資料範圍") },
            confirmButton = { TextButton(onClick = { showExportDialog = false; exportChoice = false; csvExport.launch("myair-s1-today.csv") }) { Text("單日") } },
            dismissButton = { TextButton(onClick = { showExportDialog = false; exportChoice = true; csvExport.launch("myair-s1-30-days.csv") }) { Text("近 30 日") } }
        )
        if (showScheduleDialog) AlertDialog(
            onDismissRequest = { showScheduleDialog = false },
            icon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
            title = { Text("設定本地定時量測") },
            text = { LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("排程只會在背景連線服務運作且裝置已連線時派發。斷線後會在 10 分鐘寬限內持續重連，忙碌時依序排入佇列。") }
                item { Text("一次性", style = MaterialTheme.typography.titleSmall) }
                listOf(5, 15, 30).forEach { minutes -> item {
                    OutlinedButton(onClick = { localSchedule = db.saveOnceSchedule(minutes); startBackground(); showScheduleDialog = false }, modifier = Modifier.fillMaxWidth()) { Text("$minutes 分鐘後量測一次") }
                } }
                item {
                    OutlinedTextField(value = customDelay, onValueChange = { customDelay = it.filter(Char::isDigit).take(4) }, label = { Text("自訂幾分鐘後") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Button(onClick = { customDelay.toIntOrNull()?.takeIf { it >= 1 }?.let { localSchedule = db.saveOnceSchedule(it); startBackground(); showScheduleDialog = false } }, enabled = (customDelay.toIntOrNull() ?: 0) >= 1, modifier = Modifier.fillMaxWidth()) { Text("建立自訂一次性排程") }
                }
                item { Text("週期性", style = MaterialTheme.typography.titleSmall) }
                listOf(15, 30, 60, 120, 240).forEach { minutes -> item {
                    OutlinedButton(onClick = { localSchedule = db.saveIntervalSchedule(minutes); startBackground(); showScheduleDialog = false }, modifier = Modifier.fillMaxWidth()) { Text("每 $minutes 分鐘量測") }
                } }
                item { Text("每日固定時間", style = MaterialTheme.typography.titleSmall) }
                item { OutlinedTextField(value = dailyTime, onValueChange = { dailyTime = it.take(5) }, label = { Text("24 小時制 HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item {
                    val parts = dailyTime.split(":"); val hour = parts.getOrNull(0)?.toIntOrNull(); val minute = parts.getOrNull(1)?.toIntOrNull()
                    Button(onClick = { if (hour != null && minute != null) { localSchedule = db.saveDailySchedule(hour * 60 + minute); startBackground(); showScheduleDialog = false } }, enabled = hour != null && minute != null && hour in 0..23 && minute in 0..59, modifier = Modifier.fillMaxWidth()) { Text("建立每日排程") }
                }
            } },
            confirmButton = { TextButton(onClick = { showScheduleDialog = false }) { Text("關閉") } }
        )
    }

    @Composable private fun Page(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
        LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content) }
        }
    }

    @Composable private fun OverviewPage(modifier: Modifier, state: UiState, stored: ReportPoint?) = Page(modifier) {
        val live = state.latestSession
        val pm25 = live?.latest?.pm25 ?: stored?.latestPm25
        val average = live?.averagePm25 ?: stored?.averagePm25
        val band = pm25?.let { Pm25Band.fromConcentration(it.toDouble()) }
        AirQualityPanel("最近一次量測", pm25, average, band)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusTile(Modifier.weight(1f), Icons.Outlined.BatteryFull, "裝置電量", state.latest?.batteryPercent?.let { "$it%" } ?: stored?.batteryPercent?.let { "$it%" } ?: "—")
            StatusTile(Modifier.weight(1f), Icons.Outlined.BluetoothConnected, "連線狀態", when { state.connected -> "已連線"; state.connecting -> "連線中"; else -> "未連線" })
        }
        state.latestSession?.let { SessionDetails(it) }
        if (state.latestSession == null && stored != null) SectionCard("最近一次摘要", Icons.Outlined.History) {
            Text("平均溫度 ${"%.1f".format(stored.averageTemperatureC)} °C")
            Text("平均濕度 ${"%.1f".format(stored.averageHumidityPercent)}% RH")
            Text("${stored.sampleCount} 筆樣本", style = MaterialTheme.typography.bodySmall)
            Text(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(stored.endedAt)), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable private fun StatusTile(modifier: Modifier, icon: ImageVector, title: String, value: String) {
        OutlinedCard(modifier) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        } }
    }

    @Composable private fun ConnectionPage(modifier: Modifier, state: UiState, granted: Boolean, disconnectAlert: Boolean, requestPermissions: () -> Unit, onDisconnectAlert: (Boolean) -> Unit) = Page(modifier) {
        SectionCard("藍牙連線", Icons.Outlined.Bluetooth) {
            Text(state.phase, style = MaterialTheme.typography.titleMedium)
            state.deviceName?.let { Text("裝置：$it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!granted) Button(onClick = requestPermissions, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Lock, null); Spacer(Modifier.width(8.dp)); Text("允許必要權限") }
            Button(onClick = { ble.scan() }, enabled = granted && !state.scanning && !state.connecting && !state.connected, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Search, null); Spacer(Modifier.width(8.dp)); Text(if (state.scanning) "掃描中" else "掃描並連線")
            }
        }
        SectionCard("背景連線", Icons.Outlined.Sync) {
            Text(if (state.backgroundEnabled) "背景自動重連已啟用" else "啟用後會保存偏好裝置並自動重連")
            if (!state.backgroundEnabled) Button(onClick = { startBackground() }, enabled = granted, modifier = Modifier.fillMaxWidth()) { Text("開啟背景連線") }
            else OutlinedButton(onClick = { stopBackground() }, modifier = Modifier.fillMaxWidth()) { Text("停止背景連線") }
        }
        SectionCard("斷線提醒", Icons.Outlined.NotificationsActive) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("藍牙斷線通知"); Text("背景連線意外中斷時提醒", style = MaterialTheme.typography.bodySmall) }
                Switch(checked = disconnectAlert, onCheckedChange = onDisconnectAlert)
            }
        }
    }

    @Composable private fun MeasurementPage(
        modifier: Modifier,
        state: UiState,
        queueState: MeasurementQueueState,
        schedule: LocalSchedule?,
        openSchedule: () -> Unit,
        cancelSchedule: () -> Unit,
        exportDiagnostic: () -> Unit
    ) = Page(modifier) {
        SectionCard("量測操作", Icons.Outlined.Air) {
            Button(onClick = { coordinator.request(MeasurementRequest(MeasurementOrigin.MANUAL)) }, enabled = state.connected && !state.historySyncing, modifier = Modifier.fillMaxWidth()) { Text("立即量測") }
            OutlinedButton(onClick = { ble.syncTime() }, enabled = state.connected && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("同步裝置時間") }
            OutlinedButton(onClick = { ble.syncHistoryReadOnly() }, enabled = state.connected && !state.busy && !state.historySyncing, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.historySyncing) "同步裝置紀錄中…" else "同步裝置紀錄（唯讀）")
            }
            OutlinedButton(onClick = exportDiagnostic, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("匯出診斷") }
        }
        SectionCard("任務與排程", Icons.Outlined.Schedule) {
            val current = queueState.current
            Text(current?.let { "執行中：${it.label}" } ?: "目前沒有執行中的量測", style = MaterialTheme.typography.titleMedium)
            Text("等待中的任務：${queueState.pending.size} / ${MeasurementCoordinator.MAX_PENDING}", style = MaterialTheme.typography.bodySmall)
            schedule?.let {
                val format = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault())
                Text(when (it.mode) {
                    LocalScheduleMode.ONCE -> "一次性排程"
                    LocalScheduleMode.INTERVAL -> "每 ${it.intervalMinutes} 分鐘"
                    LocalScheduleMode.DAILY -> "每天 %02d:%02d".format((it.localMinuteOfDay ?: 0) / 60, (it.localMinuteOfDay ?: 0) % 60)
                })
                Text("下次派發：${format.format(Instant.ofEpochMilli(it.nextRunAt))}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = cancelSchedule, modifier = Modifier.fillMaxWidth()) { Text("取消排程") }
            } ?: Button(onClick = openSchedule, modifier = Modifier.fillMaxWidth()) { Text("設定定時量測") }
            Text("排程派發後會先保存下一次時間；不會因前一項任務仍在執行而重設。", style = MaterialTheme.typography.bodySmall)
        }
        state.historyStatus?.let { SectionCard("裝置紀錄", Icons.Outlined.History) { Text(it) } }
        state.latestSession?.let { MeasurementResult(it) } ?: state.latest?.let { LiveResult(it) }
        Console(state.logs)
    }

    @Composable private fun MeasurementResult(summary: SessionSummary) {
        val band = Pm25Band.fromConcentration(summary.latest.pm25.toDouble())
        OutlinedCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DataCardHeader("量測結果", band)
                Text("${summary.latest.pm25} µg/m³", style = MaterialTheme.typography.displaySmall)
                Text("本次平均 ${"%.1f".format(summary.averagePm25)} µg/m³", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SessionDetails(summary)
                Text("${band.label}｜瞬時 PM2.5 濃度分級參考，非完整 AQI", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable private fun SessionDetails(summary: SessionSummary) {
        val format = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault())
        Text("溫度 ${"%.1f".format(summary.averageTemperatureC)} °C　濕度 ${"%.1f".format(summary.averageHumidityPercent)}%")
        Text("${format.format(Instant.ofEpochMilli(summary.startedAt))} ～ ${format.format(Instant.ofEpochMilli(summary.endedAt))}", style = MaterialTheme.typography.bodySmall)
        Text("${Duration.ofMillis(summary.endedAt - summary.startedAt).seconds} 秒・${summary.sampleCount} 筆樣本", style = MaterialTheme.typography.bodySmall)
    }

    @Composable private fun LiveResult(m: Measurement) { SectionCard("量測進行中", Icons.Outlined.Sensors) { Text("PM2.5 ${m.pm25} µg/m³", style = MaterialTheme.typography.headlineMedium); Text("完成後顯示平均與時間區間") } }

    @Composable private fun ReportPage(modifier: Modifier, today: List<ReportPoint>, month: List<ReportPoint>, export: () -> Unit) = Page(modifier) {
        val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        ChartCard("今日量測", today.map { ChartDatum(formatter.format(Instant.ofEpochMilli(it.endedAt)), it.averagePm25) })
        val zone = ZoneId.systemDefault()
        val daily = month.groupBy { Instant.ofEpochMilli(it.endedAt).atZone(zone).toLocalDate() }.toSortedMap().map { (date, points) ->
            ChartDatum(date.format(DateTimeFormatter.ofPattern("MM/dd")), points.map { it.averagePm25 }.average())
        }
        ChartCard("近 30 日", daily)
        SectionCard("匯出 CSV", Icons.Outlined.TableView) {
            Text("選擇要匯出的量測區間")
            Button(onClick = export, modifier = Modifier.fillMaxWidth()) { Text("選擇匯出範圍") }
        }
    }

    @Composable private fun ChartCard(title: String, data: List<ChartDatum>) { SectionCard(title, Icons.AutoMirrored.Outlined.ShowChart) { LineChart(data) } }

    @Composable private fun LineChart(data: List<ChartDatum>) {
        if (data.isEmpty()) { Box(Modifier.fillMaxWidth().height(180.dp)) { Text("此區間尚無量測資料", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return }
        val line = MaterialTheme.colorScheme.primary
        val grid = MaterialTheme.colorScheme.outlineVariant
        val max = maxOf(10.0, data.maxOf { it.value } * 1.1)
        val high = data.maxOf { it.value }
        val low = data.minOf { it.value }
        Text("最高 ${"%.1f".format(high)}　最低 ${"%.1f".format(low)} µg/m³", style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(210.dp).semantics {
            contentDescription = "PM2.5 折線圖，共 ${data.size} 筆；最高 ${"%.1f".format(high)}，最低 ${"%.1f".format(low)} 微克每立方公尺"
        }) {
            repeat(4) { row -> val y = size.height * row / 3f; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
            val path = Path()
            data.forEachIndexed { index, point ->
                val x = if (data.size == 1) size.width / 2 else size.width * index / (data.size - 1f)
                val y = size.height - (size.height * (point.value / max).toFloat())
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(line, 5.dp.toPx(), Offset(x, y))
            }
            if (data.size > 1) drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(data.first().label, style = MaterialTheme.typography.labelSmall); Text(data.last().label, style = MaterialTheme.typography.labelSmall) }
    }

    @Composable private fun DevicePage(modifier: Modifier, state: UiState, themeMode: ThemeMode, onThemeChanged: (ThemeMode) -> Unit) = Page(modifier) {
        SectionCard("裝置資訊", Icons.Outlined.Info) {
            InfoRow("韌體版本", state.firmwareVersion ?: if (state.connected) "裝置未提供" else "連線後讀取")
            state.deviceModel?.takeIf { it.isNotBlank() }?.let { InfoRow("裝置型號", it) }
            state.hardwareVersion?.let { InfoRow("硬體版本", it) }
            state.deviceProtocolVersion?.let { InfoRow("協定版本", it) }
            InfoRow("App 版本", BuildConfig.VERSION_NAME)
        }
        SectionCard("外觀", Icons.Outlined.Palette) {
            ThemeMode.entries.forEach { mode -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(mode.label); RadioButton(selected = themeMode == mode, onClick = { onThemeChanged(mode) })
            } }
        }
    }

    @Composable private fun InfoRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable private fun SectionCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        } }
    }

    @Composable private fun AirQualityPanel(title: String, value: Int?, average: Double?, band: Pm25Band?) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    band?.let { AirQualityBadge(it) }
                }
                Text(value?.let { "$it µg/m³" } ?: "尚無資料", style = MaterialTheme.typography.displaySmall)
                average?.let { Text("本次平均 ${"%.1f".format(it)} µg/m³", style = MaterialTheme.typography.titleMedium) }
                Text(band?.label ?: "完成量測後顯示空氣品質", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable private fun DataCardHeader(title: String, band: Pm25Band) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AirQualityBadge(band)
        }
    }

    @Composable private fun AirQualityBadge(band: Pm25Band) {
        val background = Color(band.backgroundArgb)
        val foreground = if (band.darkText) Color(0xFF101010) else Color.White
        Surface(color = background, contentColor = foreground, shape = CircleShape) {
            Text(band.label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
        }
    }

    @Composable private fun Console(logs: List<String>) { SectionCard("BLE 診斷 Console", Icons.Outlined.Terminal) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101713))) { SelectionContainer { LazyColumn(Modifier.fillMaxWidth().height(260.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (logs.isEmpty()) item { Text("尚無紀錄", color = Color(0xFFA9B7AE), fontFamily = FontFamily.Monospace) }
            items(logs.size) { Text(logs[it], color = Color(0xFFC8F7D8), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        } } }
    } }

    private data class Bounds(val first: Long, val second: Long, val third: Long)
    private fun reportBounds(): Bounds {
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
        return Bounds(today.atStartOfDay(zone).toInstant().toEpochMilli(), today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli())
    }
    private fun csv(monthly: Boolean): String {
        val bounds = reportBounds(); val points = db.reportPoints(if (monthly) bounds.third else bounds.first, bounds.second)
        val format = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())
        return buildString {
            appendLine("started_at,ended_at,sample_count,latest_pm25_ug_m3,average_pm25_ug_m3,average_temperature_c,average_humidity_pct,battery_pct")
            points.forEach { appendLine(listOf(format.format(Instant.ofEpochMilli(it.startedAt)), format.format(Instant.ofEpochMilli(it.endedAt)), it.sampleCount, it.latestPm25, "%.2f".format(java.util.Locale.US, it.averagePm25), "%.2f".format(java.util.Locale.US, it.averageTemperatureC), "%.2f".format(java.util.Locale.US, it.averageHumidityPercent), it.batteryPercent).joinToString(",")) }
        }
    }
    private fun startBackground() = ContextCompat.startForegroundService(this, Intent(this, S1ForegroundService::class.java))
    private fun stopBackground() = startService(Intent(this, S1ForegroundService::class.java).setAction(S1ForegroundService.ACTION_STOP))
    private fun requiredPermissions(): Array<String> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) } else add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    private fun hasPermissions(): Boolean {
        val bluetooth = if (android.os.Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return bluetooth.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }
}
