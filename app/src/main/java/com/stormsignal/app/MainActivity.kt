package com.stormsignal.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Background = Color(0xFF080D18)
private val Panel = Color(0xFF121B2B)
private val Cyan = Color(0xFF48DDF5)
private val Muted = Color(0xFF9BAAC1)

private data class SignalReading(
    val name: String,
    val source: String,
    val rssi: Int,
    val frequency: String,
    val security: String,
    val history: List<Int>
)

class MainActivity : ComponentActivity() {
    private var scanning by mutableStateOf(false)
    private var status by mutableStateOf("Ready — start a Bluetooth or Wi-Fi monitor")
    private val bleSignals = mutableStateMapOf<String, SignalReading>()
    private val wifiSignals = mutableStateMapOf<String, SignalReading>()
    private var pendingAction: (() -> Unit)? = null
    private var bleScanner: BluetoothLeScanner? = null

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
                    bleSignals = bleSignals.values.sortedByDescending { it.rssi },
                    wifiSignals = wifiSignals.values.sortedByDescending { it.rssi },
                    onBluetoothToggle = {
                        if (scanning) stopBluetoothScan()
                        else runWithPermissions(bluetoothPermissions()) { startBluetoothScan() }
                    },
                    onWifiScan = { runWithPermissions(wifiPermissions()) { scanWifi() } },
                    onOwnWifiPassword = { openOwnWifiSettings() }
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
            bleScanner = adapter.bluetoothLeScanner
            if (bleScanner == null) {
                status = "Bluetooth LE scanner unavailable"
                return
            }
            bleSignals.clear()
            scanning = true
            status = "Live BLE scan running — tap Stop to finish"
            bleScanner?.startScan(scanCallback)
        } catch (_: SecurityException) {
            scanning = false
            status = "Bluetooth permission denied"
        } catch (e: Exception) {
            scanning = false
            status = "Bluetooth scan unavailable: " + (e.message ?: "unknown error")
        }
    }

    private fun stopBluetoothScan() {
        try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) { }
        scanning = false
        status = "Bluetooth scan stopped — " + bleSignals.size + " device(s) observed"
    }

    private fun addDevice(result: ScanResult) {
        try {
            val device = result.device
            val advertisedName = result.scanRecord?.deviceName
            val name = advertisedName ?: device.name
            val key = device.address
            val displayName = name?.takeIf { it.isNotBlank() } ?: "Unnamed BLE device"
            val previous = bleSignals[key]
            val history = ((previous?.history ?: emptyList()) + result.rssi).takeLast(30)
            bleSignals[key] = SignalReading(
                name = displayName,
                source = "Bluetooth LE",
                rssi = result.rssi,
                frequency = "2.4 GHz band; exact channel not exposed by Android",
                security = "Not applicable",
                history = history
            )
        } catch (_: SecurityException) {
            status = "Bluetooth permission is required to read scan results"
        }
    }

    @Suppress("DEPRECATION")
    private fun scanWifi() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifi.isWifiEnabled) {
                status = "Turn Wi-Fi on in Android Settings first"
                return
            }
            val started = wifi.startScan()
            val results = wifi.scanResults
            if (results.isEmpty()) {
                status = if (started) "Wi-Fi scan requested; waiting for Android results" else
                    "Android is throttling Wi-Fi scans; showing no new results yet"
                return
            }
            results.forEach { result ->
                val ssid = result.SSID.takeIf { it.isNotBlank() } ?: "Hidden Wi-Fi network"
                val key = result.BSSID ?: ssid
                val previous = wifiSignals[key]
                val history = ((previous?.history ?: emptyList()) + result.level).takeLast(30)
                wifiSignals[key] = SignalReading(
                    name = ssid,
                    source = "Wi-Fi access point",
                    rssi = result.level,
                    frequency = result.frequency.toString() + " MHz",
                    security = securityLabel(result.capabilities),
                    history = history
                )
            }
            status = "Wi-Fi results updated — Android may limit scan frequency"
        } catch (_: SecurityException) {
            status = "Permission denied. Allow nearby Wi-Fi/location permissions."
        } catch (e: Exception) {
            status = "Wi-Fi scan unavailable: " + (e.message ?: "unknown error")
        }
    }

    private fun openOwnWifiSettings() {
        status = "In Android Wi-Fi settings, choose your own connected network and use Share/QR code if available"
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (_: Exception) {
            status = "Could not open Wi-Fi settings. Open Android Settings → Wi-Fi manually."
        }
    }

    private fun securityLabel(capabilities: String): String {
        val caps = capabilities.uppercase()
        return when {
            "WPA3" in caps || "SAE" in caps -> "WPA3"
            "WPA2" in caps && "WPA" in caps -> "WPA2"
            "WPA" in caps -> "WPA/WPA2"
            "WEP" in caps -> "WEP — legacy / weak"
            "OWE" in caps -> "Enhanced Open (OWE)"
            "EAP" in caps -> "Enterprise (802.1X)"
            else -> "Open / no security advertised"
        }
    }

    override fun onDestroy() {
        try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) { }
        super.onDestroy()
    }
}

@Composable
private fun Dashboard(
    status: String,
    scanning: Boolean,
    bleSignals: List<SignalReading>,
    wifiSignals: List<SignalReading>,
    onBluetoothToggle: () -> Unit,
    onWifiScan: () -> Unit,
    onOwnWifiPassword: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("STORMSIGNAL", color = Cyan, fontSize = 28.sp)
            Text("LIVE RADIO ENVIRONMENT MONITOR", color = Muted, fontSize = 12.sp)
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(18.dp)) {
                    Text("SYSTEM STATUS", color = Cyan)
                    Spacer(Modifier.height(8.dp))
                    Text(if (scanning) "LIVE MONITORING" else "STANDBY")
                    Text(status, color = Muted, fontSize = 13.sp)
                    Text("Closer to 0 dBm usually means a stronger received signal", color = Muted, fontSize = 11.sp)
                }
            }
            MonitorCard(
                title = "Bluetooth LE — live",
                description = "Nearby BLE advertisements, names and rolling RSSI graphs",
                icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = Cyan) },
                buttonText = if (scanning) "Stop live scan" else "Start live scan",
                onClick = onBluetoothToggle
            )
            if (bleSignals.isEmpty()) {
                Text("Bluetooth: no readings yet. Start the live scan and keep nearby devices advertising.", color = Muted, fontSize = 12.sp)
            } else {
                bleSignals.forEach { SignalCard(it) }
            }
            MonitorCard(
                title = "Wi-Fi — nearby access points",
                description = "SSID, signal, frequency and advertised Wi-Fi security",
                icon = { Icon(Icons.Filled.Wifi, contentDescription = null, tint = Cyan) },
                buttonText = "Refresh Wi-Fi readings",
                onClick = onWifiScan
            )
            if (wifiSignals.isEmpty()) {
                Text("Wi-Fi results appear after Android provides a scan. Android may throttle refreshes.", color = Muted, fontSize = 12.sp)
            } else {
                wifiSignals.forEach { SignalCard(it) }
            }
            MonitorCard(
                title = "Coverage and limitations",
                description = "Built-in tablet radios do not expose a full-spectrum RF analyzer",
                icon = { Icon(Icons.Filled.Insights, contentDescription = null, tint = Cyan) },
                buttonText = "Limitations",
                onClick = { }
            )
            Text(
                "Passwords are not broadcast by nearby Wi-Fi networks. To access a password for your own network, use Android's built-in Share/QR-code option. This app cannot extract other networks' credentials.",
                color = Muted,
                fontSize = 12.sp
            )
            Button(
                onClick = onOwnWifiPassword,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF193D52))
            ) {
                Text("Open Wi-Fi settings for my network", color = Cyan)
            }
        }
    }
}

@Composable
private fun SignalCard(signal: SignalReading) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(signal.name, color = Color.White, fontSize = 15.sp)
            Text(signal.source + " • " + signal.rssi + " dBm", color = Cyan, fontSize = 12.sp)
            Text("Frequency: " + signal.frequency, color = Muted, fontSize = 11.sp)
            Text("Security: " + signal.security, color = if (signal.security.contains("legacy") || signal.security.startsWith("Open")) Color(0xFFFFC36B) else Muted, fontSize = 11.sp)
            Text("Recent strength history", color = Muted, fontSize = 10.sp)
            SignalGraph(signal.history)
            Text("Graph range: −100 to −30 dBm • latest sample " + signal.rssi + " dBm", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SignalGraph(history: List<Int>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
        val minDbm = -100f
        val maxDbm = -30f
        for (dbm in listOf(-90f, -70f, -50f)) {
            val y = size.height * (maxDbm - dbm) / (maxDbm - minDbm)
            drawLine(color = Muted.copy(alpha = 0.22f), start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        if (history.isEmpty()) return@Canvas
        val points = history.mapIndexed { index, value ->
            val x = if (history.size == 1) size.width else size.width * index / (history.size - 1).toFloat()
            val bounded = value.coerceIn(minDbm.toInt(), maxDbm.toInt()).toFloat()
            val y = size.height * (maxDbm - bounded) / (maxDbm - minDbm)
            Offset(x, y)
        }
        if (points.size == 1) {
            drawCircle(color = Cyan, radius = 4.dp.toPx(), center = points.first())
        } else {
            points.zipWithNext().forEach { (a, b) ->
                drawLine(color = Cyan, start = a, end = b, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(color = Cyan, radius = 3.dp.toPx(), center = points.last())
        }
    }
}

@Composable
private fun MonitorCard(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    buttonText: String,
    onClick: () -> Unit
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
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF193D52))
            ) { Text(buttonText, color = Cyan) }
        }
    }
}
