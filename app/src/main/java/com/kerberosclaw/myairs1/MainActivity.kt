package com.kerberosclaw.myairs1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val app get() = application as MyAirApplication
    private val db get() = app.database
    private val ble get() = app.bleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF006C4C))) { App() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun App() {
        val state by ble.state.collectAsState()
        var granted by remember { mutableStateOf(hasPermissions()) }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPermissions() }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer -> writer.write(db.exportJson()) } }
        }
        Scaffold(topBar = { TopAppBar(title = { Text("myAir S1 私人版") }, actions = {
            TextButton(onClick = { export.launch("myair-s1-diagnostic.json") }) { Text("匯出診斷") }
        }) }) { padding ->
            LazyColumn(Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ConnectionCard(state, granted, { permissions.launch(requiredPermissions()) }) }
                state.latestSession?.let { summary -> item { MeasurementCard(summary) } }
                    ?: state.latest?.let { measurement -> item { LiveMeasurementCard(measurement) } }
                item { Console(state.logs) }
            }
        }
    }

    @Composable private fun ConnectionCard(state: UiState, granted: Boolean, requestPermissions: () -> Unit) {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.phase, style = MaterialTheme.typography.titleMedium)
            state.deviceName?.let { Text("裝置：$it") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!granted) Button(onClick = requestPermissions) { Text("允許必要權限") }
                Button(onClick = { ble.scan() }, enabled = granted && !state.scanning) { Text(if (state.scanning) "掃描中" else "掃描並連線") }
                Button(onClick = { ble.measure() }, enabled = state.connected && !state.busy) { Text("立即量測") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ble.syncTime() }, enabled = state.connected && !state.busy) { Text("同步裝置時間") }
                if (!state.backgroundEnabled) Button(onClick = { startBackground() }, enabled = granted) { Text("開啟背景連線") }
                else OutlinedButton(onClick = { stopBackground() }) { Text("停止背景連線") }
            }
            Text(if (state.backgroundEnabled) "背景自動重連已啟用" else "背景自動重連未啟用", style = MaterialTheme.typography.bodySmall)
        } }
    }

    @Composable private fun MeasurementCard(summary: SessionSummary) {
        val band = Pm25Band.fromConcentration(summary.latest.pm25.toDouble())
        val background = Color(band.backgroundArgb)
        val foreground = if (band.darkText) Color(0xFF101010) else Color.White
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault())
        val elapsed = Duration.ofMillis((summary.endedAt - summary.startedAt).coerceAtLeast(0))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = background, contentColor = foreground)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("最新 PM2.5  ${summary.latest.pm25} µg/m³", style = MaterialTheme.typography.headlineMedium)
                Text("本次平均  ${"%.1f".format(summary.averagePm25)} µg/m³", style = MaterialTheme.typography.titleLarge)
                Text("最新：${summary.latest.temperatureC} °C　${summary.latest.humidityPercent} %RH　電量 ${summary.latest.batteryPercent}%")
                Text("平均：${"%.1f".format(summary.averageTemperatureC)} °C　${"%.1f".format(summary.averageHumidityPercent)} %RH")
                HorizontalDivider(color = foreground.copy(alpha = 0.35f))
                Text("量測區間：${formatter.format(Instant.ofEpochMilli(summary.startedAt))} ～ ${formatter.format(Instant.ofEpochMilli(summary.endedAt))}")
                Text("歷時：${elapsed.seconds} 秒　樣本：${summary.sampleCount} 筆　觸發：${summary.latest.trigger}")
                Text("${band.label}｜瞬時 PM2.5 濃度分級參考，非完整 AQI", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable private fun LiveMeasurementCard(m: Measurement) {
        val band = Pm25Band.fromConcentration(m.pm25.toDouble())
        val foreground = if (band.darkText) Color(0xFF101010) else Color.White
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(band.backgroundArgb), contentColor = foreground)) {
            Column(Modifier.padding(16.dp)) {
                Text("量測進行中：PM2.5  ${m.pm25} µg/m³", style = MaterialTheme.typography.headlineMedium)
                Text("完成後將顯示本次平均、量測時間區間與樣本數")
            }
        }
    }

    @Composable private fun Console(logs: List<String>) {
        Text("BLE 診斷 Console", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth().height(280.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF101713))) {
            SelectionContainer { LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (logs.isEmpty()) item { Text("尚無紀錄。若失敗，請從右上角匯出診斷 JSON。", color = Color(0xFFA9B7AE), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                items(logs.size) { index -> Text(logs[index], color = Color(0xFFC8F7D8), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            } }
        }
    }

    private fun startBackground() = ContextCompat.startForegroundService(this, Intent(this, S1ForegroundService::class.java))
    private fun stopBackground() = startService(Intent(this, S1ForegroundService::class.java).setAction(S1ForegroundService.ACTION_STOP))
    private fun requiredPermissions(): Array<String> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        else add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    private fun hasPermissions() = requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}
