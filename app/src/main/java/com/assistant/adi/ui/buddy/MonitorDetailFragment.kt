package com.assistant.adi.ui.buddy

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.assistant.adi.R
import com.assistant.adi.ui.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MonitorDetailFragment : Fragment() {
    private val device: BuddyDeviceViewModel by activityViewModels()
    private val battery: BatteryViewModel by viewModels()
    private val ram: RamCpuViewModel by viewModels()
    private var body: LinearLayout? = null
    private var forwarded = false
    override fun onResume() {
        super.onResume()
        if (forwarded || parentFragmentManager.isStateSaved || !isAdded) return
        val monitor = runCatching { Monitor.valueOf(requireArguments().getString("monitor").orEmpty()) }.getOrDefault(Monitor.BATTERY)
        forwarded = true
        val previousTag = "monitor_${monitor.name.lowercase()}"
        if (parentFragmentManager.backStackEntryCount > 0) parentFragmentManager.popBackStack(previousTag, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        (activity as? DashboardActivity)?.navigateSection(previousTag)
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val (scroll, content) = BuddyPageUi(requireContext()).page(); body = content; return scroll
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        val monitor = runCatching { Monitor.valueOf(requireArguments().getString("monitor").orEmpty()) }.getOrDefault(Monitor.BATTERY)
        val page = BuddyPageUi(requireContext()); val ui = page.ui; val body = body ?: return
        val card = MonitorCard(ui, body, monitor)
        val time = page.text("Membaca data…", 13f, secondary = true, stage = true); body.addView(time, ui.margin(bottom = 18))
        val accent = when (monitor) {
            Monitor.BATTERY -> R.color.buddy_mint
            Monitor.TEMPERATURE -> R.color.buddy_peach
            Monitor.STORAGE, Monitor.MEMORY -> R.color.buddy_lilac
            Monitor.NETWORK -> R.color.buddy_sky
        }
        val adviceCard = page.card(body, accent)
        adviceCard.addView(page.text("Yang bisa kamu lakukan", 18f, true), ui.margin(bottom = 8))
        val advice = page.text("Saran akan mengikuti data yang tersedia."); adviceCard.addView(advice)
        page.section(body, "Pahami kondisinya")
        val explanation = page.disclosure(body, "Arti bacaan ini", "Menunggu bacaan perangkat.")
        val facts = page.disclosure(body, "Sumber & rincian", "Belum tersedia")
        val history = page.disclosure(body, "Catatan 24 jam terakhir", "Belum ada catatan. Aktifkan Simpan riwayat di Pencatatan riwayat.")
        fun stamp(timestamp: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
        when (monitor) {
            Monitor.BATTERY, Monitor.TEMPERATURE -> battery.logs24Hours.observe(viewLifecycleOwner) { logs ->
                val recent = logs.sortedByDescending { it.timestamp }.take(5)
                history.text = if (recent.isEmpty()) "Belum ada sampel dalam 24 jam terakhir. Atur pencatatan melalui tombol di bawah." else recent.joinToString("\n\n") { "${stamp(it.timestamp)}  ·  ${if (monitor == Monitor.BATTERY) "${it.percentage}%" else String.format(java.util.Locale.getDefault(), "%.1f °C", it.temperature)}" }
            }
            Monitor.MEMORY -> ram.ramLogs24Hours.observe(viewLifecycleOwner) { logs ->
                history.text = if (logs.isEmpty()) "Belum ada sampel RAM dalam 24 jam terakhir." else logs.sortedByDescending { it.timestamp }.take(5).joinToString("\n\n") { "${stamp(it.timestamp)}  ·  ${it.usedRam} / ${it.totalRam} MB dipakai" }
            }
            Monitor.NETWORK -> history.text = "Buka Jaringan untuk melihat koneksi dan menjalankan tes ping."
            Monitor.STORAGE -> history.text = "Riwayat penyimpanan belum dicatat. Kartu di atas menunjukkan ruang tersedia saat ini; Buddy tidak memindai file pribadimu."
        }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            device.snapshot.collect { snapshot ->
                val reading = snapshot?.readings?.find { it.monitor == monitor } ?: return@collect
                card.update(reading, BuddyProfile(requireContext()).casual)
                explanation.text = reading.explanation; advice.text = reading.advice; facts.text = reading.facts
                time.text = "Diperbarui ${stamp(snapshot.timestamp)}"
            }
        } }
        if (monitor == Monitor.STORAGE) body.addView(page.button("Buka penyimpanan Android", icon = R.drawable.ic_ms_settings) {
            runCatching { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }.onFailure { Toast.makeText(requireContext(), "Menu tidak tersedia. Buka Pengaturan Android → Penyimpanan.", Toast.LENGTH_LONG).show() }
        }, ui.margin())
        else body.addView(page.button("Lihat grafik & riwayat", icon = R.drawable.ic_ms_history) {
            navigate(when (monitor) { Monitor.BATTERY, Monitor.TEMPERATURE -> "battery"; Monitor.MEMORY -> "ram"; else -> "network" })
        }, ui.margin())
        body.addView(page.button("Buka Pencatatan riwayat", secondary = true, icon = R.drawable.ic_ms_history) { navigate("background") }, ui.margin())
    }
    private fun navigate(tag: String) { (requireActivity() as DashboardActivity).navigateSection(tag) }
    override fun onDestroyView() { body = null; super.onDestroyView() }
    companion object { fun create(monitor: Monitor) = MonitorDetailFragment().apply { arguments = Bundle().apply { putString("monitor", monitor.name) } } }
}
