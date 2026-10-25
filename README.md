# StormSignal

StormSignal is an Android app for read-only local radio-environment monitoring. It provides a dark dashboard for nearby Bluetooth Low Energy advertisements and Android-reported Wi-Fi scan results.

## Features
- Bluetooth LE discovery for 10 seconds.
- RSSI (received signal strength) for detected Bluetooth LE advertisements.
- Wi-Fi scan results with SSID, RSSI in dBm, and frequency in MHz.
- Android runtime permission prompts and a dark Material 3 interface.
- GitHub Actions workflow to build a debug APK artifact.

## Safety and limitations
- This app does not block, jam, spoof, or interfere with radio communications.
- RSSI is approximate and affected by walls, antenna design, orientation, and device hardware.
- Android may limit Wi-Fi scan frequency or return cached results.
- Bluetooth scanning focuses on BLE advertisements; it does not identify every Bluetooth device or decode private communications.
- Scan results are displayed locally and are not uploaded by this project.

## Build without a PC
1. Open the repository's **Actions** tab.
2. Select **Build StormSignal APK**.
3. If needed, select **Run workflow**.
4. Open the completed run and download the `StormSignal-debug` artifact ZIP.
5. Extract `app-debug.apk` and open it on your Android tablet. Android may ask you to allow installation from your file/browser app.

## Stack
Kotlin, Jetpack Compose, Material 3, Android SDK 35, Java 17, GitHub Actions.
