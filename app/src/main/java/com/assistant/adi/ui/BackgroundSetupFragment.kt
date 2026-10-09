package com.assistant.adi.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.assistant.adi.MainApplication
import com.assistant.adi.R
import com.assistant.adi.ui.buddy.withActionIcons
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.data.AppRepository
import com.assistant.adi.service.MonitoringService
import com.assistant.adi.service.SamplingCadence
import com.assistant.adi.service.MyNotificationListener
import com.assistant.adi.ui.buddy.BuddyUi
import com.assistant.adi.ui.buddy.BuddyPageUi
import com.assistant.adi.worker.WatchdogWorker
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackgroundSetupFragment : Fragment() {
    private var body: LinearLayout? = null
    private var panel: BottomSheetDialog? = null
    private var picker: androidx.appcompat.app.AlertDialog? = null
    private var loadingApps = false
    private val page get() = BuddyPageUi(requireContext())
    private val ui get() = BuddyUi(page.ui.context, surface = true)
    private val prefs get() = PrefsManager(requireContext())
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val (scroll, content) = page.page(); body = content; return scroll
    }
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val body = body ?: return; body.removeAllViews()
        val settings = prefs
        val status = when {
            !settings.monitoringEnabled -> "Dijeda"
            settings.lastSampleTime == 0L || System.currentTimeMillis() - settings.lastSampleTime > (settings.refreshIntervalMinutes + 10) * 60_000L -> "Menunggu pembaruan"
            else -> "Aktif"
        }
        val summary = page.card(body, R.color.buddy_mint)
        summary.addView(ui.text(status, 21f, true), ui.margin(bottom = 4))
        summary.addView(ui.text(if (settings.lastSampleTime == 0L) "Sampel terakhir: belum ada. Android dapat menunda pembaruan." else "Sampel terakhir ${age(settings.lastSampleTime)}. Android dapat menunda pembaruan.", 14f), ui.margin(bottom = 8))
        toggle(summary, "Simpan riwayat", "Baterai, memori, dan jaringan", settings.monitoringEnabled) { on ->
            settings.monitoringEnabled = on
            if (on) enableMonitoring(resetCoverage = true)
            else {
                settings.continuousMonitoring = false; WatchdogWorker.cancel(requireContext())
                requireContext().stopService(Intent(requireContext(), MonitoringService::class.java))
            }
            render()
        }
        ui.row(summary, "Jadwal", "Setiap ${settings.refreshIntervalMinutes} menit") { schedulePanel() }
        summary.addView(ui.button("Perbarui sekarang", secondary = true, icon = R.drawable.ic_ms_refresh) { requestSample() }, ui.margin(top = 4))
        page.section(body, "Pilihan pencatatan")
        val features = ui.card(body).apply { setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4)) }
        ui.row(features, "Catatan notifikasi", if (prefs.logNotificationContent) "Nama aplikasi, waktu, dan isi pesan" else "Nama aplikasi dan waktu") { notificationPanel() }
        ui.divider(features)
        ui.row(features, "Pemeriksaan internet", if (settings.latencyProbeEnabled) "Aktif · memakai sedikit data" else "Mati · opsional") { networkPanel() }
        ui.divider(features)
        ui.row(features, "Periksa izin & pengaturan HP", "Akses dan pembatasan baterai") { (requireActivity() as DashboardActivity).navigateSection("permissions") }
        page.section(body, "Masa simpan riwayat")
        val retention = ui.card(body).apply { setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4)) }
        ui.row(retention, "Hapus catatan otomatis", "Semua riwayat disimpan ${prefs.autoClearNotifDays} hari") { retentionPanel() }
    }
    private fun age(time: Long): String {
        val minutes = (System.currentTimeMillis() - time).coerceAtLeast(0) / 60_000
        return when { minutes == 0L -> "baru saja"; minutes < 60 -> "$minutes menit lalu"; minutes < 1440 -> "${minutes / 60} jam lalu"; else -> "${minutes / 1440} hari lalu" }
    }
    private fun showPanel(title: String, description: String, content: (LinearLayout, BottomSheetDialog) -> Unit) {
        panel?.dismiss()
        val dialog = BottomSheetDialog(ui.context); panel = dialog
        val (scroll, body) = ui.page()
        scroll.setBackgroundColor(ui.color(R.color.buddy_tile))
        body.addView(ui.heading(title), ui.margin(top = 16, bottom = 12))
        body.addView(ui.text(description, 15f, secondary = true), ui.margin(bottom = 20))
        content(body, dialog)
        body.addView(ui.textButton("Tutup", icon = R.drawable.ic_ms_close) { dialog.dismiss() }, ui.margin(top = 12, bottom = 0))
        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.show()
    }
    private fun toggle(parent: LinearLayout, title: String, description: String, checked: Boolean, action: (Boolean) -> Unit) {
        parent.addView(MaterialSwitch(ui.context).apply {
            text = title; textSize = 16f; minHeight = ui.dp(56); isChecked = checked
            contentDescription = "$title. $description"
            setOnCheckedChangeListener { _, on -> action(on) }
        }, ui.margin(bottom = 0))
        if (description.isNotBlank()) parent.addView(ui.text(description, 13f, secondary = true), ui.margin(bottom = 12))
    }
    private fun enableMonitoring(resetCoverage: Boolean) {
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            if (resetCoverage) withContext(Dispatchers.IO) { AppRepository(context).beginMonitoringCoverage(System.currentTimeMillis()) }
            if (isAdded) MainApplication.startMonitoringService(context)
        }
    }
    private fun requestSample() {
        if (!prefs.monitoringEnabled) feedback("Aktifkan Simpan riwayat terlebih dahulu")
        else if (!SamplingCadence.isDue(System.currentTimeMillis(), prefs.lastSampleTime, prefs.refreshIntervalMinutes.coerceIn(15, 60) * 60_000L)) feedback("Catatan sudah terbaru")
        else { WatchdogWorker.sampleNow(requireContext()); feedback("Pembaruan dijadwalkan. Android bisa menundanya.") }
    }
    private fun notificationAccess() = NotificationManagerCompat.getEnabledListenerPackages(requireContext()).contains(requireContext().packageName)
    private fun notificationPanel(): Unit = showPanel("Catatan notifikasi", "Simpan nama aplikasi dan waktu notifikasi. Isi pesannya tidak disimpan secara bawaan.") { body, dialog ->
        body.addView(ui.text("Android memberi akses luas untuk membaca notifikasi saat izin ini aktif. Pilih di bawah apa yang boleh disimpan Buddy. Perubahan berlaku untuk notifikasi baru.", 14f), ui.margin())
        body.addView(ui.button("Periksa izin aplikasi", icon = R.drawable.ic_ms_shield) {
            dialog.dismiss(); (requireActivity() as DashboardActivity).navigateSection("permissions")
        }, ui.margin())
        toggle(body, "Simpan juga isi pesan", "Bisa memuat informasi pribadi", prefs.logNotificationContent) { on ->
            if (!on) { prefs.logNotificationContent = false; feedback("Isi pesan tidak lagi dicatat") }
            else {
                dialog.dismiss()
                MaterialAlertDialogBuilder(ui.context).setTitle("Simpan isi notifikasi?")
                    .setMessage("Isi pesan dapat memuat informasi pribadi. Penyaringan kode OTP tidak selalu mengenali semua rahasia. Catatan yang sudah tersimpan tetap ada sampai masa simpannya habis.")
                    .setNegativeButton("Batal") { _, _ -> notificationPanel() }
                    .setPositiveButton("Simpan isi pesan") { _, _ -> prefs.logNotificationContent = true; notificationPanel() }.show().withActionIcons(R.drawable.ic_ms_save, R.drawable.ic_ms_close)
            }
        }
        ui.row(body, "Aplikasi yang tidak dicatat", "${prefs.excludedPackages.size} aplikasi dikecualikan") { dialog.dismiss(); chooseExcludedApps() }
        if (notificationAccess()) body.addView(ui.textButton("Sambungkan ulang catatan notifikasi", icon = R.drawable.ic_ms_refresh) {
            runCatching { android.service.notification.NotificationListenerService.requestRebind(ComponentName(requireContext(), MyNotificationListener::class.java)) }
                .onSuccess { feedback("Penyambungan ulang diminta") }.onFailure { feedback("Belum tersambung. Periksa izin catatan notifikasi.") }
        }, ui.margin())
    }
    private fun networkPanel() = showPanel("Pemeriksaan internet", "Ukur waktu respons koneksi pada jadwal pencatatan.") { body, _ ->
        body.addView(ui.text("Menghubungi google.com dan memakai sedikit data. Layanan itu menerima alamat IP dan waktu koneksi. Isi chat dan notifikasi tidak dikirim.", 15f), ui.margin())
        toggle(body, "Periksa secara berkala", "Jenis koneksi tetap terbaca meski pilihan ini mati", prefs.latencyProbeEnabled) { on -> prefs.latencyProbeEnabled = on; render() }
        ui.disclosure(body, "Kapan perubahan berlaku?", "Pilihan ini mengikuti Simpan riwayat. Mematikannya menghentikan pemeriksaan berikutnya, bukan permintaan yang sudah berlangsung.")
    }
    private fun retentionPanel() = showPanel("Masa simpan riwayat", "Pilihan penyimpanan global ini bukan izin aplikasi.") { body, dialog ->
        body.addView(ui.text("Berlaku untuk catatan notifikasi, baterai, RAM, dan jaringan.", 14f, secondary = true), ui.margin(bottom = 8))
        val days = (listOf(1, 7, 30, 90) + prefs.autoClearNotifDays).distinct().sorted()
        val group = RadioGroup(ui.context)
        days.forEach { day -> group.addView(RadioButton(ui.context).apply {
            id = day
            text = "$day hari"
            minHeight = ui.dp(48)
            isChecked = day == prefs.autoClearNotifDays
        }) }
        body.addView(group, ui.margin())
        body.addView(ui.button("Simpan masa simpan", icon = R.drawable.ic_ms_save) {
            prefs.autoClearNotifDays = group.checkedRadioButtonId.takeIf { it in days } ?: prefs.autoClearNotifDays
            dialog.dismiss()
            render()
            feedback("Masa simpan riwayat disimpan")
        }, ui.margin())
    }
    private fun schedulePanel() = showPanel("Jadwal pencatatan", "Pilih seberapa sering kondisi HP disimpan. Android dapat menunda jadwal saat HP beristirahat.") { body, dialog ->
        val options = intArrayOf(15, 30, 45, 60)
        val group = RadioGroup(ui.context)
        options.forEach { minutes -> group.addView(RadioButton(ui.context).apply {
            id = minutes; text = "Setiap $minutes menit"; minHeight = ui.dp(48)
            isChecked = prefs.refreshIntervalMinutes == minutes
        }) }
        body.addView(group, ui.margin())
        body.addView(ui.button("Simpan jadwal", icon = R.drawable.ic_ms_save) {
            prefs.refreshIntervalMinutes = group.checkedRadioButtonId.takeIf { it in options } ?: 15
            WatchdogWorker.schedule(requireContext()); dialog.dismiss(); render(); feedback("Jadwal disimpan")
        }, ui.margin())
        body.addView(ui.textButton("Perbarui catatan sekarang", icon = R.drawable.ic_ms_refresh) {
            requestSample()
        }, ui.margin())
    }
    private fun chooseExcludedApps() {
        if (loadingApps) return
        loadingApps = true; feedback("Memuat daftar aplikasi…")
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    val pm = context.packageManager
                    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                        .distinctBy { it.activityInfo.packageName }
                        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                        .sortedBy { it.second.lowercase() }
                }
                if (!isAdded || view == null) return@launch
                if (apps.isEmpty()) { feedback("Daftar aplikasi belum tersedia. Pengecualianmu tetap tersimpan."); return@launch }
                val selected = PrefsManager(context).excludedPackages.toMutableSet()
                val repeatedNames = apps.groupingBy { it.second }.eachCount()
                val labels = apps.map { if ((repeatedNames[it.second] ?: 0) > 1) "${it.second}\n${it.first}" else it.second }.toTypedArray()
                picker = MaterialAlertDialogBuilder(ui.context).setTitle("Jangan catat aplikasi ini")
                    .setMultiChoiceItems(labels, apps.map { it.first in selected }.toBooleanArray()) { _, index, checked ->
                        if (checked) selected.add(apps[index].first) else selected.remove(apps[index].first)
                    }.setNegativeButton("Batal", null)
                    .setPositiveButton("Simpan") { _, _ -> PrefsManager(context).excludedPackages = selected; feedback("Pengecualian disimpan"); notificationPanel() }.show().withActionIcons(R.drawable.ic_ms_save, R.drawable.ic_ms_close)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { if (isAdded) feedback("Daftar aplikasi belum bisa dibaca. Coba lagi.") }
            finally { loadingApps = false }
        }
    }
    private fun feedback(message: String) { if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show() }
    override fun onDestroyView() { panel?.dismiss(); picker?.dismiss(); panel = null; picker = null; body = null; super.onDestroyView() }
}
