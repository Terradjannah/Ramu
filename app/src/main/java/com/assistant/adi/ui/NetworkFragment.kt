package com.assistant.adi.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.assistant.adi.R
import com.assistant.adi.ui.buddy.withActionIcons
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.databinding.FragmentNetworkBinding
import com.assistant.adi.ui.buddy.MonitorDetailUi

class NetworkFragment : Fragment() {
    private var _binding: FragmentNetworkBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NetworkViewModel by viewModels()
    private var connectionInfo = "Rincian koneksi belum tersedia"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNetworkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (resources.configuration.screenWidthDp < 360 || resources.configuration.fontScale > 1.3f) {
            binding.networkRates.orientation = LinearLayout.VERTICAL
            for (index in 0 until binding.networkRates.childCount) {
                val child = binding.networkRates.getChildAt(index)
                child.layoutParams = (child.layoutParams as LinearLayout.LayoutParams).apply { width = -1; weight = 0f }
            }
        }

        MonitorDetailUi(this).installInfoAction(requireActivity(), viewLifecycleOwner, "Jaringan",
            { getString(R.string.network_info, connectionInfo) },
            { getString(R.string.network_info_source) })
        viewModel.connectionType.observe(viewLifecycleOwner) { binding.tvConnectionType.text = it }
        viewModel.networkDetails.observe(viewLifecycleOwner) { connectionInfo = it }
        viewModel.downloadSpeed.observe(viewLifecycleOwner) { binding.tvDownloadSpeed.text = it }
        viewModel.uploadSpeed.observe(viewLifecycleOwner) { binding.tvUploadSpeed.text = it }
        viewModel.pingState.observe(viewLifecycleOwner) { renderPing(it) }
        viewModel.selectedPingTarget.observe(viewLifecycleOwner) { target ->
            val id = if (target == "1.1.1.1") R.id.btn_cloudflare_dns else R.id.btn_google_dns
            if (binding.toggleDns.checkedButtonId != id) binding.toggleDns.check(id)
        }
        binding.toggleDns.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) when (checkedId) {
                R.id.btn_google_dns -> viewModel.setPingTarget("8.8.8.8")
                R.id.btn_cloudflare_dns -> viewModel.setPingTarget("1.1.1.1")
            }
        }
        binding.btnTestPing.setOnClickListener { viewModel.triggerPingTest() }
        binding.btnPingThreshold.setOnClickListener { showPingThresholdDialog() }
        renderPing(viewModel.pingState.value ?: PingState.Idle)
    }

    private fun renderPing(state: PingState) {
        val threshold = PrefsManager(requireContext()).pingAlertThresholdMs
        val (label, icon, surface, tint) = when (state) {
            PingState.Idle -> PingAppearance(R.string.network_ping_idle, R.drawable.ic_ms_network_ping, R.color.buddy_stage, R.color.buddy_sub)
            is PingState.Running -> PingAppearance(R.string.network_ping_running, R.drawable.ic_ms_network_ping, R.color.buddy_stage, R.color.buddy_sub)
            is PingState.Success -> if (state.latencyMs <= threshold)
                PingAppearance(R.string.network_ping_normal, R.drawable.ic_ms_check_circle, R.color.buddy_mint, R.color.buddy_stage_action)
            else PingAppearance(R.string.network_ping_slow, R.drawable.ic_ms_warning, R.color.buddy_peach, R.color.buddy_stage_warning)
            is PingState.Timeout -> PingAppearance(R.string.network_ping_timeout, R.drawable.ic_ms_error, R.color.buddy_danger_surface, R.color.buddy_danger)
            is PingState.Error -> PingAppearance(R.string.network_ping_error, R.drawable.ic_ms_error, R.color.buddy_danger_surface, R.color.buddy_danger)
        }
        binding.panelPing.setCardBackgroundColor(ContextCompat.getColor(requireContext(), surface))
        binding.panelPing.strokeWidth = if (state is PingState.Success && state.latencyMs > threshold)
            (resources.displayMetrics.density + 0.5f).toInt() else 0
        binding.panelPing.strokeColor = ContextCompat.getColor(requireContext(), R.color.buddy_warning)
        binding.tvPingStatus.setText(label)
        binding.tvPingStatus.setTextColor(ContextCompat.getColor(requireContext(), tint))
        binding.ivPingStatus.setImageResource(icon)
        binding.ivPingStatus.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), tint))
        binding.ivPingStatus.contentDescription = getString(label)
        binding.pingProgress.visibility = if (state is PingState.Running) View.VISIBLE else View.GONE
        binding.btnTestPing.isEnabled = state !is PingState.Running
        binding.btnGoogleDns.isEnabled = state !is PingState.Running
        binding.btnCloudflareDns.isEnabled = state !is PingState.Running
        binding.tvPingLatency.text = if (state is PingState.Success) getString(R.string.network_ping_latency, state.latencyMs) else "—"
        binding.tvPingThresholdStatus.text = getString(R.string.network_ping_threshold, threshold)
    }

    private data class PingAppearance(val label: Int, val icon: Int, val surface: Int, val tint: Int)

    private fun showPingThresholdDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(PrefsManager(requireContext()).pingAlertThresholdMs.toString())
            selectAll()
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Batas waktu respons")
            .setMessage("Masukkan 10 sampai 60000 ms. Batas ini hanya ditampilkan di halaman Jaringan.")
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .show().withActionIcons(R.drawable.ic_ms_save, R.drawable.ic_ms_close)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toIntOrNull()
            if (value == null || value !in 10..60000) input.error = "Masukkan 10 sampai 60000 ms."
            else {
                PrefsManager(requireContext()).pingAlertThresholdMs = value
                renderPing(viewModel.pingState.value ?: PingState.Idle)
                dialog.dismiss()
            }
        }
    }

    override fun onStart() { super.onStart(); viewModel.start() }
    override fun onStop() { viewModel.stop(); super.onStop() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
