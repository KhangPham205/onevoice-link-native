package com.onevoice.link.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.provider.Settings
import java.io.File

/**
 * Once-a-second readout for the demo screen: proof of offline operation and
 * live memory, visible in an uncut screen recording.
 *
 * Memory comes from /proc rather than ActivityManager.getProcessMemoryInfo,
 * which Android rate-limits for app callers; polling it every second would
 * show stale numbers. RSS excludes DSP-private HTP allocations, exactly as
 * `peak_rss_kb` in the bench logs does.
 */
data class DeviceVitals(
    val rssKb: Long,
    val peakRssKb: Long,
    val memAvailableKb: Long,
    val memTotalKb: Long,
    val airplaneMode: Boolean,
    /** False when no network with INTERNET capability is active. */
    val networkConnected: Boolean,
    /** Whether the installed APK declares android.permission.INTERNET at all. */
    val internetPermission: Boolean,
    /** PowerManager.THERMAL_STATUS_*; -1 when unavailable. */
    val thermalStatus: Int,
) {
    val offline: Boolean get() = !networkConnected

    companion object {
        fun read(context: Context): DeviceVitals {
            var rss = 0L
            var peak = 0L
            runCatching {
                File("/proc/self/status").forEachLine { line ->
                    when {
                        line.startsWith("VmRSS:") -> rss = line.kbField()
                        line.startsWith("VmHWM:") -> peak = line.kbField()
                    }
                }
            }
            var total = 0L
            var available = 0L
            runCatching {
                File("/proc/meminfo").forEachLine { line ->
                    when {
                        line.startsWith("MemTotal:") -> total = line.kbField()
                        line.startsWith("MemAvailable:") -> available = line.kbField()
                    }
                }
            }
            val airplane = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0,
            ) != 0
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val connected = runCatching {
                connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }.getOrDefault(false)
            val internet = context.packageManager.checkPermission(
                Manifest.permission.INTERNET,
                context.packageName,
            ) == PackageManager.PERMISSION_GRANTED
            val thermal = runCatching {
                context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1
            }.getOrDefault(-1)
            return DeviceVitals(
                rssKb = rss,
                peakRssKb = peak,
                memAvailableKb = available,
                memTotalKb = total,
                airplaneMode = airplane,
                networkConnected = connected,
                internetPermission = internet,
                thermalStatus = thermal,
            )
        }

        private fun String.kbField(): Long =
            substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
    }
}
