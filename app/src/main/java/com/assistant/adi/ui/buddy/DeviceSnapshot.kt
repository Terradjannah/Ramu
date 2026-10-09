package com.assistant.adi.ui.buddy

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.adi.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.util.Locale

enum class Monitor(val title: String, val icon: Int) {
    BATTERY("Baterai", R.drawable.ic_battery), TEMPERATURE("Suhu baterai", R.drawable.ic_sensors),
    STORAGE("Penyimpanan", R.drawable.ic_storage), MEMORY("RAM / memori", R.drawable.ic_ram_cpu), NETWORK("Jaringan", R.drawable.ic_network)
}
enum class Condition(val label: String, val tone: Int) {
    UNKNOWN("Belum tersedia", R.color.buddy_sub), GOOD("Normal", R.color.buddy_action),
    ATTENTION("! Perhatian", R.color.buddy_warning), ACTION("! Perlu tindakan", R.color.buddy_danger)
}
data class MonitorReading(
    val monitor: Monitor, val value: String = "Belum ada", val condition: Condition = Condition.UNKNOWN,
    val friendly: String = "Datanya belum terbaca. Coba buka lagi sebentar.",
    val explanation: String = "Android belum menyediakan data ini. Nilai kosong bukan berarti nol.",
    val advice: String = "Kembali ke Beranda atau buka ulang aplikasi untuk membaca data lagi.",
    val facts: String = "Tidak ada angka yang dapat ditampilkan.", val percent: Int? = null,
    val rawValue: Double? = null, val charging: Boolean? = null, val compactValue: String = value,
    val totalBytes: Long? = null, val availableBytes: Long? = null
)
data class DeviceSnapshot(val readings: List<MonitorReading>, val timestamp: Long)

/** No background polling, network probes, file enumeration, or new runtime permissions. */
class BuddyDeviceViewModel(app: Application) : AndroidViewModel(app) {
    private val context = getApplication<Application>()

    val snapshot = merge(
        callbackFlow<BatteryRefresh> {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    trySend(when (intent.action) {
                        Intent.ACTION_POWER_CONNECTED -> BatteryRefresh(intent = null, event = BuddyChargingState.Event.PowerConnected)
                        Intent.ACTION_POWER_DISCONNECTED -> BatteryRefresh(intent = null, event = BuddyChargingState.Event.PowerDisconnected)
                        else -> BatteryRefresh(intent, BuddyChargingState.Event.BatteryChanged(intent.chargingSignal()))
                    })
                }
            }
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            val sticky = context.registerReceiver(receiver, filter)
            trySend(BatteryRefresh(sticky, BuddyChargingState.Event.Initial(sticky.chargingSignal())))
            awaitClose { context.unregisterReceiver(receiver) }
        },
        flow {
            while (true) {
                delay(SNAPSHOT_INTERVAL_MILLIS)
                val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                emit(BatteryRefresh(sticky, BuddyChargingState.Event.Poll(sticky.chargingSignal())))
            }
        }
    ).scan(ChargingRefresh(BuddyChargingState(), null)) { current, refresh ->
        ChargingRefresh(current.state.reduce(refresh.event), refresh)
    }.drop(1).conflate().map { current ->
        readSnapshot(current.refresh?.intent, current.state.charging)
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)

    private fun readSnapshot(batteryIntent: Intent?, charging: Boolean?): DeviceSnapshot {
        val readings = Monitor.entries.map { monitor ->
            runCatching { read(context, monitor, batteryIntent, charging) }
                .getOrElse { MonitorReading(monitor, friendly = "Belum bisa dibaca dari Android.") }
        }
        return DeviceSnapshot(readings, System.currentTimeMillis())
    }
    private fun read(context: Context, monitor: Monitor, batteryIntent: Intent?, charging: Boolean?): MonitorReading {
        val variant = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) % 2
        when (monitor) {
            Monitor.BATTERY, Monitor.TEMPERATURE -> {
                val intent = batteryIntent ?: return MonitorReading(monitor)
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (monitor == Monitor.BATTERY) {
                    if (level < 0 || scale <= 0) return MonitorReading(monitor)
                    val pct = (level.toLong() * 100 / scale).toInt().coerceIn(0, 100)
                    val isCharging = charging == true
                    val condition = when { pct <= 10 && !isCharging -> Condition.ACTION; pct <= 20 && !isCharging -> Condition.ATTENTION; else -> Condition.GOOD }
                    return MonitorReading(monitor, "$pct%", condition,
                        if (isCharging) "Lagi mengisi tenaga, tunggu sebentar ya." else if (pct <= 20) "Mulai lapar nih, cari charger yuk." else listOf("Masih punya tenaga buat menemanimu.", "Baterainya masih kenyang, nih.")[variant],
                        "Persentase menunjukkan sisa daya, bukan kesehatan atau kapasitas asli baterai. Daya tahan berubah mengikuti aplikasi, layar, dan koneksi.",
                        if (pct <= 20 && !isCharging) "Siapkan pengisi daya. Aktifkan penghemat baterai jika belum bisa mengisi." else if (isCharging) "Letakkan HP di tempat berventilasi. Kurangi aktivitas berat jika terasa panas saat mengisi." else "Belum ada tindakan mendesak. Atur kecerahan sesuai kebutuhan agar lebih nyaman dan hemat daya.",
                        "${if (isCharging) "Sedang mengisi daya" else "Menggunakan baterai"}\nSumber: status baterai Android", pct,
                        rawValue = pct.toDouble(), charging = charging)
                }
                if (!intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) return MonitorReading(monitor)
                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) / 10f
                if (!temp.isFinite() || temp < -20 || temp > 100) return MonitorReading(monitor)
                val condition = when { temp >= 45 -> Condition.ACTION; temp >= 40 || temp < 0 -> Condition.ATTENTION; else -> Condition.GOOD }
                return MonitorReading(monitor, String.format(Locale.getDefault(), "%.1f °C", temp), condition,
                    if (temp >= 45) "Lagi gerah, beri waktu istirahat yuk." else if (temp >= 40) "Mulai hangat. Lagi banyak aktivitas, ya?" else if (temp < 0) "Baterainya sedang sangat dingin." else listOf("Suhunya nyaman untuk saat ini.", "Belum terasa gerah dari bacaan sensor.")[variant],
                    "Ini suhu sensor baterai, bukan suhu CPU atau seluruh permukaan HP. Game, kamera, cuaca, dan pengisian daya dapat membuatnya naik. Label perhatian mulai 40 °C dan tindakan mulai 45 °C adalah panduan umum, bukan batas resmi pabrikan.",
                    if (temp >= 40) "Jeda game atau kamera dan pindahkan HP dari sinar matahari. Jika panas saat mengisi, hentikan pengisian sementara dan biarkan dingin alami." else if (temp < 0) "Bawa HP ke suhu ruangan dan hindari pemanasan langsung." else "Tidak ada tindakan mendesak dari bacaan ini. Tetap beri ruang agar panas dapat keluar.",
                    "Sumber: sensor baterai Android\nBacaan bisa berbeda antarperangkat", rawValue = temp.toDouble())
            }
            Monitor.STORAGE -> {
                val stats = StatFs(context.filesDir.absolutePath)
                val total = stats.totalBytes; val free = stats.availableBytes
                if (total <= 0) return MonitorReading(monitor)
                val used = total - free; val pct = (used * 100 / total).toInt().coerceIn(0, 100)
                val condition = when { pct >= 95 -> Condition.ACTION; pct >= 85 -> Condition.ATTENTION; else -> Condition.GOOD }
                return MonitorReading(monitor, "${gb(free)} GB", condition,
                    if (pct >= 85) "Tas penyimpananku mulai penuh nih." else listOf("Masih ada ruang untuk cerita baru.", "Ruang kosongnya masih lega.")[variant],
                    "Angka utama adalah ruang yang masih tersedia di penyimpanan internal. Android, aplikasi, unduhan, dan media berbagi ruang ini. Total dapat berbeda dari kapasitas pada kemasan.",
                    if (pct >= 85) "Tinjau unduhan dan video besar melalui pengaturan penyimpanan Android. Cadangkan file penting sebelum menghapusnya." else "Belum perlu membersihkan apa pun. Tinjau file sesekali dan simpan cadangan foto penting.",
                    "${gb(used)} GB terpakai dari ${gb(total)} GB\n${gb(free)} GB tersedia · $pct% terpakai\nFile pribadi tidak dibaca atau dihapus oleh Buddy.", pct,
                    totalBytes = total, availableBytes = free)
            }
            Monitor.MEMORY -> {
                val info = ActivityManager.MemoryInfo()
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
                if (info.totalMem <= 0) return MonitorReading(monitor)
                val used = info.totalMem - info.availMem; val pct = (used * 100 / info.totalMem).toInt().coerceIn(0, 100)
                val condition = if (info.lowMemory) Condition.ATTENTION else Condition.GOOD
                return MonitorReading(monitor, "$pct%", condition,
                    if (info.lowMemory) "Lagi banyak yang dipikirkan. Jeda dulu yuk." else listOf("Android masih bisa mengatur napasnya.", "Memori sedang membantu aplikasi bekerja.")[variant],
                    "RAM adalah ruang kerja sementara. Android sengaja memakai RAM untuk cache agar aplikasi cepat dibuka. Persentase tinggi saja bukan tanda masalah; label perhatian mengikuti sinyal memori rendah dari Android.",
                    if (info.lowMemory) "Jika HP terasa lambat, tutup aktivitas berat yang sudah tidak dipakai. Hindari aplikasi pembersih RAM otomatis." else "Tidak perlu rutin mengosongkan RAM. Biarkan Android mengelola aplikasi dan cache.",
                    "${gb(used)} GB dipakai dari ${gb(info.totalMem)} GB\n${gb(info.availMem)} GB tersedia\nMemori rendah menurut Android: ${if (info.lowMemory) "ya" else "tidak"}", pct,
                    rawValue = used.toDouble(), compactValue = "${gb(used)} GB")
            }
            Monitor.NETWORK -> {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                val connected = caps != null; val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                val type = when {
                    caps == null -> "Offline"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Seluler"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "Terhubung"
                }
                return MonitorReading(monitor, type, if (validated) Condition.GOOD else Condition.ATTENTION,
                    if (!connected) "Lagi offline. Buddy lokal tetap bisa menemani." else if (!validated) "Tersambung, tapi internet belum terverifikasi." else listOf("Koneksi internetnya sudah terverifikasi.", "Jaringan sedang bersahabat.")[variant],
                    "Status ini mengikuti koneksi aktif dan pemeriksaan internet oleh Android. Terhubung ke Wi-Fi saja belum menjamin akses internet. Ini bukan pengukuran kecepatan atau kekuatan sinyal.",
                    if (!connected) "Aktifkan Wi-Fi atau data seluler bila dibutuhkan. Chat dengan model yang sudah diunduh tetap dapat dipakai offline." else if (!validated) "Periksa apakah Wi-Fi meminta login atau data seluler masih tersedia." else "Tidak ada tindakan mendesak. Kecepatan nyata tetap bergantung pada sinyal dan layanan yang dipakai.",
                    "Koneksi aktif: $type\nInternet: ${if (validated) "terverifikasi Android" else "belum terverifikasi"}\nHalaman ini tidak melakukan tes internet. Pemeriksaan berkala diatur terpisah dalam Pencatatan riwayat.")
            }
        }
    }
    private fun gb(bytes: Long) = String.format(Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024 * 1024))

    private companion object {
        const val SNAPSHOT_INTERVAL_MILLIS = 30_000L
    }

    private fun Intent?.chargingSignal(): Boolean? {
        val intent = this ?: return null
        return when (val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, Int.MIN_VALUE)) {
            0 -> false
            in 1..Int.MAX_VALUE -> true
            else -> when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, Int.MIN_VALUE)) {
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> null
            }
        }
    }

    private data class BatteryRefresh(val intent: Intent?, val event: BuddyChargingState.Event)
    private data class ChargingRefresh(val state: BuddyChargingState, val refresh: BatteryRefresh?)
}

