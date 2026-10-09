package com.assistant.adi.ui

import android.os.Bundle
import android.content.ClipData
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.assistant.adi.ui.buddy.withActionIcons
import com.assistant.adi.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnExportCsv.setOnClickListener {
            viewModel.exportCSV { files ->
                if (!isAdded) return@exportCSV
                if (files != null) {
                    try {
                        val contentUris = files.map { file ->
                            androidx.core.content.FileProvider.getUriForFile(
                                requireContext(),
                                "${requireContext().packageName}.fileprovider",
                                file
                            )
                        }
                        val sharedClipData = ClipData.newUri(
                            requireContext().contentResolver,
                            "Ekspor CSV",
                            contentUris.first()
                        )
                        contentUris.drop(1).forEach { sharedClipData.addItem(ClipData.Item(it)) }
                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "text/csv"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(contentUris))
                            clipData = sharedClipData
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, "Bagikan ekspor CSV"))
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Belum dapat membagikan CSV: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), "Ekspor CSV belum berhasil", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnBackupDb.setOnClickListener {
            viewModel.backupDatabase { success ->
                if (!isAdded) return@backupDatabase
                if (success) {
                    Toast.makeText(requireContext(), "Cadangan lokal berhasil dibuat", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Cadangan lokal belum berhasil dibuat", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnRestoreDb.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(binding.root.context)
                .setTitle("Pulihkan cadangan lokal?")
                .setMessage("Database saat ini akan diganti dengan cadangan yang tersimpan. Data yang lebih baru dapat hilang. Buat cadangan terlebih dahulu jika masih diperlukan.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Pulihkan") { _, _ -> restoreBackup() }.show().withActionIcons(com.assistant.adi.R.drawable.ic_ms_restart_alt, com.assistant.adi.R.drawable.ic_ms_close)
        }
    }

    private fun restoreBackup() {
        viewModel.restoreDatabase { success ->
            if (!isAdded) return@restoreDatabase
            if (success) {
                Toast.makeText(requireContext(), "Database dipulihkan. Semua layar diperbarui otomatis.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), "Cadangan belum dapat dipulihkan. Pastikan file cadangan tersedia.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
