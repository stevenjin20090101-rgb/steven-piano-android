// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.diag

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.HardwarePropertiesManager
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import androidx.annotation.RequiresApi
import dev.stevenjin.stevenpiano.BuildConfig
import java.io.File
import kotlin.math.roundToInt

/** What the tablet and the app say of themselves now (v1.18 — M46, the web panel's System page). */
fun interface SystemProbe {
    fun read(): SystemReading
}

/**
 * The tablet and the app now, as [AndroidSystemProbe] reads them: every field null when the tablet can't say (an older
 * Android, a reading only a device owner gets, a call that failed). Pure data: the web panel's JSON is built from it.
 */
data class SystemReading(
    val app: AppReading = AppReading(),
    /** "Manufacturer Model". */
    val model: String? = null,
    /** Android's release, "14". */
    val android: String? = null,
    /** Since the tablet started (`SystemClock.elapsedRealtime`). */
    val uptimeMs: Long? = null,
    val screenOn: Boolean? = null,
    val battery: BatteryReading = BatteryReading(),
    val thermal: ThermalReading = ThermalReading(),
    val memory: MemoryReading = MemoryReading(),
    val storage: StorageReading = StorageReading(),
    val cpu: CpuReading = CpuReading(),
    val network: NetworkReading = NetworkReading(),
) {
    /** Memory available, as a whole percent of the total; null when either is unknown. */
    val memoryAvailablePct: Int?
        get() {
            val total = memory.total ?: return null
            val available = memory.available ?: return null
            return if (total > 0) (available * 100 / total).toInt().coerceIn(0, 100) else null
        }

    companion object {
        /** `PowerManager.THERMAL_STATUS_*`, 0 to 6, as the panel names them. */
        val THERMAL_WORDS = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")
    }
}

/** The app itself: its version, its process, its memory, its share of one core since the last read, and the kiosk's hold. */
data class AppReading(
    val version: String? = null,
    val build: Int? = null,
    val pid: Int? = null,
    /** The `Threads:` line of `/proc/self/status`. */
    val threads: Int? = null,
    /** Since the process started. */
    val uptimeMs: Long? = null,
    val heapUsed: Long? = null,
    val heapMax: Long? = null,
    val nativeHeap: Long? = null,
    /** CPU time over wall time since the last read, as a percent of one core, one decimal; null on the first read. */
    val cpuPct: Double? = null,
    val deviceOwner: Boolean? = null,
    /** The screen is locked to the app now (lock task mode). */
    val kiosk: Boolean? = null,
)

/**
 * The battery, from the sticky `ACTION_BATTERY_CHANGED`: [percent], [charging] (charging or full), [plug] (`ac`, `usb`,
 * `wireless`, `dock`, `none`), [tempC] (one decimal), [voltageMv], [health] (`good`, `overheat`, `cold`, `dead`,
 * `overVoltage`, `unknown`).
 */
data class BatteryReading(
    val percent: Int? = null,
    val charging: Boolean? = null,
    val plug: String? = null,
    val tempC: Double? = null,
    val voltageMv: Int? = null,
    val health: String? = null,
)

/**
 * Heat: Android's thermal [status] (0–6, [SystemReading.THERMAL_WORDS]; Android 10+), the [headroom] forecast 10 s ahead
 * (Android 11+; 1.0 is where throttling starts), and the hottest CPU and skin sensors in °C, which Android gives only to
 * a device owner (the school tablet is one).
 */
data class ThermalReading(val status: Int? = null, val headroom: Double? = null, val cpuC: Double? = null, val skinC: Double? = null)

/** `ActivityManager.MemoryInfo`: [total] and [available] bytes, and whether Android counts memory as [low]. */
data class MemoryReading(val total: Long? = null, val available: Long? = null, val low: Boolean? = null)

/** The app's files' volume: [total] and [free] (usable) bytes. */
data class StorageReading(val total: Long? = null, val free: Long? = null)

/** The [cores], and the whole tablet's load since the last read in percent (a device owner's reading; null on the first). */
data class CpuReading(val cores: Int? = null, val loadPct: Double? = null)

/** The default network: [online], its [transport] (`wifi`, `ethernet`, `cellular`, `vpn`, `other`), its signal and its downstream estimate. */
data class NetworkReading(val online: Boolean? = null, val transport: String? = null, val signalDbm: Int? = null, val downKbps: Int? = null)

/**
 * The tablet's readings for the System page (v1.18 — M46, BUILD_SPEC.md). Every read is guarded (the API level, a
 * `SecurityException`, any other failure): what the tablet cannot say reads as null, and [read] never throws. The two
 * CPU shares are worked out between one read and the next, so the first gives none. Thread-safe.
 */
class AndroidSystemProbe(
    context: Context,
    private val version: String = BuildConfig.VERSION_NAME,
    private val build: Int = BuildConfig.VERSION_CODE,
) : SystemProbe {
    private val app: Context = context.applicationContext

    /** The tablet's CPU counters at the last read (active ms, total ms, summed over the cores), and the app's CPU time with when. */
    private var lastLoad: Pair<Long, Long>? = null
    private var lastAppCpu: Pair<Long, Long>? = null

    @Synchronized
    override fun read(): SystemReading {
        val owner = guarded { app.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(app.packageName) }
        val power = guarded { app.getSystemService(PowerManager::class.java) }
        // CPU and skin temperatures, and the CPU's counters, are a device owner's to read: anyone else gets a SecurityException.
        val hardware = if (owner == true) guarded { app.getSystemService(HardwarePropertiesManager::class.java) } else null
        return SystemReading(
            app = appReading(owner),
            model = guarded { "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifEmpty { null } },
            android = Build.VERSION.RELEASE,
            uptimeMs = SystemClock.elapsedRealtime(),
            screenOn = guarded { power?.isInteractive },
            battery = battery(app) ?: BatteryReading(),
            thermal = ThermalReading(
                status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalStatus(power) else null,
                headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) headroom(power) else null,
                cpuC = hottest(hardware, HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU),
                skinC = hottest(hardware, HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN),
            ),
            memory = memory(),
            storage = storage(),
            cpu = CpuReading(cores = Runtime.getRuntime().availableProcessors(), loadPct = load(hardware)),
            network = network(),
        )
    }

    private fun appReading(owner: Boolean?): AppReading {
        val runtime = Runtime.getRuntime()
        return AppReading(
            version = version,
            build = build,
            pid = Process.myPid(),
            threads = guarded { threads() },
            uptimeMs = guarded { SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() }?.takeIf { it >= 0 },
            heapUsed = runtime.totalMemory() - runtime.freeMemory(),
            heapMax = runtime.maxMemory().takeIf { it != Long.MAX_VALUE },
            nativeHeap = guarded { Debug.getNativeHeapAllocatedSize() },
            cpuPct = appCpu(),
            deviceOwner = owner,
            kiosk = guarded { app.getSystemService(ActivityManager::class.java)?.lockTaskModeState?.let { it != ActivityManager.LOCK_TASK_MODE_NONE } },
        )
    }

    /** The process's threads now, from `/proc/self/status`. */
    private fun threads(): Int? =
        File(PROC_STATUS).useLines { lines -> lines.firstOrNull { it.startsWith(THREADS) } }?.substringAfter(':')?.trim()?.toIntOrNull()

    /** The app's CPU time since the last read over the wall time between them, as a percent of one core. */
    private fun appCpu(): Double? {
        val cpu = guarded { Process.getElapsedCpuTime() } ?: return null
        val wall = SystemClock.elapsedRealtime()
        val before = lastAppCpu
        lastAppCpu = cpu to wall
        if (before == null) return null
        val wallMs = wall - before.second
        if (wallMs <= 0) return null
        return oneDecimal((cpu - before.first).coerceAtLeast(0) * PERCENT / wallMs)
    }

    /** The whole tablet's CPU load between the last read and this one, from the cores' active and total time. */
    private fun load(hardware: HardwarePropertiesManager?): Double? {
        val usages = hardware?.let { guarded { it.cpuUsages } }
        if (usages == null) {
            lastLoad = null
            return null
        }
        var active = 0L
        var total = 0L
        for (usage in usages) {
            if (usage == null) continue   // an offline core
            active += usage.active
            total += usage.total
        }
        val before = lastLoad
        lastLoad = active to total
        if (before == null) return null
        val totalMs = total - before.second
        val activeMs = active - before.first
        if (totalMs <= 0 || activeMs < 0) return null
        return oneDecimal(activeMs * PERCENT / totalMs).coerceIn(0.0, PERCENT)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun thermalStatus(power: PowerManager?): Int? = guarded { power?.currentThermalStatus }?.takeIf { it in SystemReading.THERMAL_WORDS.indices }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun headroom(power: PowerManager?): Double? =
        guarded { power?.getThermalHeadroom(HEADROOM_SECONDS)?.toDouble() }?.takeIf { it.isFinite() }?.let { Math.round(it * 100) / 100.0 }

    /** The hottest of the sensors of [type] now, one decimal; null without a device owner's reading. */
    private fun hottest(hardware: HardwarePropertiesManager?, type: Int): Double? = guarded {
        hardware?.getDeviceTemperatures(type, HardwarePropertiesManager.TEMPERATURE_CURRENT)
            ?.filter { it.isFinite() && it != HardwarePropertiesManager.UNDEFINED_TEMPERATURE }
            ?.maxOrNull()
            ?.let { oneDecimal(it.toDouble()) }
    }

    private fun memory(): MemoryReading = guarded {
        val activities = app.getSystemService(ActivityManager::class.java) ?: return@guarded null
        val info = ActivityManager.MemoryInfo().also { activities.getMemoryInfo(it) }
        MemoryReading(info.totalMem, info.availMem, info.lowMemory)
    } ?: MemoryReading()

    private fun storage(): StorageReading = guarded {
        val stat = StatFs(app.filesDir.path)
        StorageReading(stat.totalBytes, stat.availableBytes)
    } ?: StorageReading()

    private fun network(): NetworkReading = guarded {
        val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return@guarded null
        val network = connectivity.activeNetwork ?: return@guarded NetworkReading(online = false)
        val caps = connectivity.getNetworkCapabilities(network) ?: return@guarded NetworkReading(online = false)
        NetworkReading(
            online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            },
            signalDbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) signal(caps) else null,
            downKbps = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 },
        )
    } ?: NetworkReading()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun signal(caps: NetworkCapabilities): Int? = caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }

    companion object {
        private const val PROC_STATUS = "/proc/self/status"
        private const val THREADS = "Threads:"
        private const val PERCENT = 100.0

        /** How far ahead the thermal headroom is forecast, in seconds. */
        private const val HEADROOM_SECONDS = 10

        /** `BatteryManager.BATTERY_PLUGGED_DOCK` (Android 13), by value, so older Androids compile against it too. */
        private const val PLUGGED_DOCK = 8

        /**
         * The battery now, from the sticky `ACTION_BATTERY_CHANGED` (no receiver is registered); null when Android gives
         * none. The firmware updater's [dev.stevenjin.stevenpiano.firmware.batteryState] reads it here too.
         */
        fun battery(context: Context): BatteryReading? {
            val intent = guarded { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            return BatteryReading(
                percent = if (level >= 0 && scale > 0) (level * PERCENT / scale).roundToInt().coerceIn(0, 100) else null,
                charging = if (status < 0) null else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                plug = plugOf(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)),
                tempC = if (temperature == Int.MIN_VALUE) null else temperature / TENTHS,
                voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 },
                health = healthOf(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)),
            )
        }

        private const val TENTHS = 10.0

        private fun plugOf(plugged: Int): String? = when (plugged) {
            0 -> "none"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            PLUGGED_DOCK -> "dock"
            else -> null
        }

        private fun healthOf(health: Int): String? = when (health) {
            -1 -> null
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "overVoltage"
            else -> "unknown"
        }

        private fun oneDecimal(value: Double): Double = Math.round(value * TENTHS) / TENTHS
    }
}

/** [block]'s answer, or null when it threw: a reading the tablet won't give is no reading, never a crash. */
private inline fun <T> guarded(block: () -> T?): T? = try {
    block()
} catch (e: SecurityException) {
    null
} catch (e: Exception) {
    null
}
