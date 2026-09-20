package com.kerberosclaw.myairs1

import android.Manifest
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private lateinit var ble: BleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase(this)
        ble = BleManager(this, db)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF006C4C))) { App() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun App() {
        val state by ble.state.collectAsState()
        var granted by remember { mutableStateOf(hasPermissions()) }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPermissions() }
        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(db.exportJson()) }
        }
        Scaffold(topBar = { TopAppBar(title = { Text("myAir S1 私人版") }, actions = {
            TextButton(onClick = { exportLauncher.launch("myair-s1-diagnostic.json") }) { Text("匯出診斷") }
        }) }) { padding ->
            LazyColumn(Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.phase, style = MaterialTheme.typography.titleMedium)
                        state.deviceName?.let { Text("裝置：$it  ${state.address.orEmpty()}") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!granted) Button(onClick = { permissionLauncher.launch(requiredPermissions()) }) { Text("允許藍牙權限") }
                            Button(onClick = { ble.scan() }, enabled = granted && !state.scanning) { Text(if (state.scanning) "掃描中" else "掃描並連線") }
                            Button(onClick = { ble.measure() }, enabled = state.connected && !state.busy) { Text("立即量測") }
                        }
                        OutlinedButton(onClick = { ble.syncTime() }, enabled = state.connected && !state.busy) { Text("同步裝置時間") }
                    } }
                }
                state.latest?.let { m -> item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE1F5EA))) {
                        Column(Modifier.padding(16.dp)) {
                            Text("PM2.5  ${m.pm25} µg/m³", style = MaterialTheme.typography.headlineMedium)
                            Text("${m.temperatureC} °C　${m.humidityPercent} %RH　電量 ${m.batteryPercent}%")
                            Text("觸發：${m.trigger}　裝置時間：${m.timestampUtc ?: "未同步"}")
                        }
                    }
                } }
                item {
                    Text("BLE 診斷 Console", style = MaterialTheme.typography.titleMedium)
                    Card(
                        Modifier.fillMaxWidth().height(280.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF101713))
                    ) {
                        SelectionContainer {
                            LazyColumn(
                                Modifier.fillMaxSize().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                if (state.logs.isEmpty()) item {
                                    Text(
                                        "尚無紀錄。若失敗，請從右上角匯出診斷 JSON。",
                                        color = Color(0xFFA9B7AE),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                items(state.logs.size) { index ->
                                    Text(
                                        state.logs[index],
                                        color = Color(0xFFC8F7D8),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> = if (android.os.Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions() = requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    override fun onDestroy() { ble.disconnect(); db.close(); super.onDestroy() }
}
