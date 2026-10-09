package com.assistant.adi.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.assistant.adi.R
import com.assistant.adi.databinding.FragmentStorageBinding
import com.assistant.adi.ui.buddy.BuddyDeviceViewModel
import com.assistant.adi.ui.buddy.BuddyPageUi
import com.assistant.adi.ui.buddy.Monitor
import com.assistant.adi.ui.buddy.MonitorDetailUi
import kotlinx.coroutines.launch
import java.util.Locale

class StorageFragment : Fragment() {
    private val device: BuddyDeviceViewModel by activityViewModels()
    private var binding: FragmentStorageBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        binding = FragmentStorageBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val binding = binding ?: return
        val detailUi = MonitorDetailUi(this)
        binding.btnOpenStorage.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
                .onFailure { Toast.makeText(requireContext(), "Menu tidak tersedia. Buka Pengaturan Android → Penyimpanan.", Toast.LENGTH_LONG).show() }
        }
        detailUi.installInfoAction(requireActivity(), viewLifecycleOwner, "Penyimpanan",
            "Ruang tersedia, total, dan terpakai pada penyimpanan internal. Grafik menunjukkan komposisi saat ini; riwayat penyimpanan belum direkam.",
            "StatFs pada direktori data aplikasi. Angka menggambarkan ruang yang dilaporkan Android, bukan pemindaian file.")

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                device.snapshot.collect { snapshot ->
                    val reading = snapshot?.readings?.firstOrNull { it.monitor == Monitor.STORAGE } ?: return@collect
                    val total = reading.totalBytes
                    val free = reading.availableBytes
                    if (total == null || free == null || total <= 0 || free < 0 || free > total) {
                        binding.tvStorageAvailable.text = "Data belum tersedia"; binding.tvStorageTotals.text = "Android belum memberikan kapasitas penyimpanan."
                        binding.storageComposition.removeAllViews(); binding.tvStorageLegend.text = "Komposisi belum tersedia."
                    } else {
                        val used = total - free
                        binding.tvStorageAvailable.text = String.format(Locale.getDefault(), "%.1f GB", free / 1_073_741_824.0)
                        binding.tvStorageTotals.text = String.format(Locale.getDefault(), "%.1f GB dipakai dari %.1f GB", used / 1_073_741_824.0, total / 1_073_741_824.0)
                        binding.storageComposition.removeAllViews()
                        val usedPart = (used.toDouble() / total).toFloat().coerceIn(0f, 1f)
                        val height = (16 * resources.displayMetrics.density).toInt()
                        if (usedPart > 0f) binding.storageComposition.addView(View(requireContext()).apply { setBackgroundColor(requireContext().getColor(R.color.buddy_chart_blue)) }, LinearLayout.LayoutParams(0, height, usedPart))
                        if (usedPart < 1f) binding.storageComposition.addView(View(requireContext()).apply { setBackgroundColor(requireContext().getColor(R.color.buddy_track)) }, LinearLayout.LayoutParams(0, height, 1f - usedPart))
                        binding.tvStorageLegend.text = String.format(Locale.getDefault(), "Biru: terpakai %.0f%%  ·  Netral: tersedia %.0f%%", usedPart * 100, (1f - usedPart) * 100)
                    }
                }
            }
        }
    }

    override fun onDestroyView() { binding = null; super.onDestroyView() }
}
