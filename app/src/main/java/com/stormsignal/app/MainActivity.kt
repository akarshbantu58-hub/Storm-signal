package com.stormsignal.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Background = Color(0xFF080D18)
private val Panel = Color(0xFF121B2B)
private val Cyan = Color(0xFF48DDF5)
private val Muted = Color(0xFF9BAAC1)

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var scanning by mutableStateOf(false)
    private var status by mutableStateOf("Ready — choose a monitor")
    private val devices = mutableStateListOf<String>()
    private var wifiSummary by mutableStateOf("Not scanned yet")
    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) pendingAction?.invoke()
            else status = "Permission needed to scan nearby radio devices"
            pendingAction = null
        }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { addDevice(result) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { addDevice(it) } }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status = "Bluetooth scan failed (code " + errorCode + ")"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Background, surface = Panel)) {
                Dashboard(
                    status = status,
                    scanning = scanning,
                    devices = devices.toList(),
                    wifiSummary = wifiSummary,
                    onBluetoothScan = { runWithPermissions(bluetoothPermissions()) { startBluetoothScan() } },
                    onWifiScan = { runWithPermissions(wifiPermissions()) { scanWifi() } }
                )
            }
        }
    }

    private fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun wifiPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun runWithPermissions(permissions: Array<String>, action: () -> Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action() else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startBluetoothScan() {
        try {
            val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter
            if (adapter == null || !adapter.isEnabled) {
                status = "Turn Bluetooth on in Android Settings first"
                return
            }
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                status = "Bluetooth LE scanner unavailable"
                return
            }
            devices.clear()
            scanning = true
            status = "Scanning nearby Bluetooth LE advertisements for 10 seconds…"
            scanner.startScan(scanCallback)
            handler.postDelayed({
                try { scanner.stopScan(scanCallback) } catch (_: SecurityException) { }
                scanning = false
                status = "Bluetooth scan complete — " + devices.size + " result(s)"
            }, 10_000)
        } catch (_: SecurityException) {
            scanning = false
            status = "Bluetooth permission denied"
        } catch (e: Exception) {
            scanning = false
            status = "Bluetooth scan unavailable: " + (e.message ?: "unknown error")
        }
    }

    private fun addDevice(result: ScanResult) {
        val name = try { result.device.name ?: "Unnamed device" } catch (_: SecurityException) { "Unnamed device" }
        val entry = name + " • RSSI " + result.rssi + " dBm"
        if (!devices.contains(entry)) devices.add(entry)
    }

    @Suppress("DEPRECATION")
    private fun scanWifi() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifi.isWifiEnabled) {
                wifiSummary = "Turn Wi-Fi on in Android Settings first"
                return
            }
            wifi.startScan()
            val results = wifi.scanResults
            wifiSummary = if (results.isEmpty()) {
                "No results available yet. Try again shortly; Android may throttle scans."
            } else {
                results.sortedByDescending { it.level }.take(12).joinToString("\n") { result ->
                    val label = result.SSID.ifBlank { "Hidden network" }
                    label + " • " + result.level + " dBm • " + result.frequency + " MHz"
                }
            }
            status = "Wi-Fi scan requested — Android controls scan timing"
        } catch (_: SecurityException) {
            wifiSummary = "Permission denied. Allow nearby Wi-Fi/location permissions."
        } catch (e: Exception) {
            wifiSummary = "Wi-Fi scan unavailable: " + (e.message ?: "unknown error")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) { }
    }
}

@Composable
private fun Dashboard(
    status: String,
    scanning: Boolean,
    devices: List<String>,
    wifiSummary: String,
    onBluetoothScan: () -> Unit,
    onWifiScan: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("STORMSIGNAL", color = Cyan, fontSize = 28.sp)
            Text("RADIO ENVIRONMENT MONITOR", color = Muted, fontSize = 12.sp)
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(18.dp)) {
                    Text("SYSTEM STATUS", color = Cyan)
                    Spacer(Modifier.height(8.dp))
                    Text(if (scanning) "MONITORING" else "STANDBY")
                    Text(status, color = Muted, fontSize = 13.sp)
                }
            }
            MonitorCard(
                title = "Bluetooth LE",
                description = "Nearby BLE advertisements and received signal strength",
                icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = Cyan) },
                buttonText = if (scanning) "Scanning…" else "Scan Bluetooth",
                onClick = onBluetoothScan,
                enabled = !scanning
            )
            if (devices.isNotEmpty()) ResultCard("BLUETOOTH RESULTS", devices)
            MonitorCard(
                title = "Wi-Fi",
                description = "Android-reported network names, signal level and frequency",
                icon = { Icon(Icons.Filled.Wifi, contentDescription = null, tint = Cyan) },
                buttonText = "Scan Wi-Fi",
                onClick = onWifiScan
            )
            ResultCard("WI-FI RESULTS", wifiSummary.split("\n"))
            MonitorCard(
                title = "Signal Analysis",
                description = "RSSI is approximate and varies with walls, orientation and hardware",
                icon = { Icon(Icons.Filled.Insights, contentDescription = null, tint = Cyan) },
                buttonText = "Analysis info",
                onClick = { }
            )
            HorizontalDivider(color = Panel)
            Text(
                "Read-only monitoring • No radio interference",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun MonitorCard(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    buttonText: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                icon()
                Column {
                    Text(title, color = Color.White)
                    Text(description, color = Muted, fontSize = 12.sp)
                }
            }
            Button(
                onClick = onClick,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF193D52))
            ) { Text(buttonText, color = Cyan) }
        }
    }
}

@Composable
private fun ResultCard(title: String, lines: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(title, color = Cyan, fontSize = 12.sp)
            lines.forEach { Text(it, color = Muted, fontSize = 12.sp) }
        }
    }
}
