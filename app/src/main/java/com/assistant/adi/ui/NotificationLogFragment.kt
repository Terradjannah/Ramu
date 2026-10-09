package com.assistant.adi.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.assistant.adi.R
import com.assistant.adi.databinding.FragmentNotificationLogBinding

class NotificationLogFragment : Fragment() {

    private var _binding: FragmentNotificationLogBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NotificationViewModel by viewModels()
    private val feedAdapter = NotificationAdapter()
    private val statsAdapter = NotifStatAdapter()
    
    private var isFirstSpinnerSelection = true
    private var storedNotificationCount = 0
    private var filteredNotificationCount = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotificationLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup Recyclers
        binding.rvNotificationsFeed.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNotificationsFeed.adapter = feedAdapter

        binding.rvNotificationsStats.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNotificationsStats.adapter = statsAdapter

        // Set up View Toggles (Feed vs Stats)
        binding.toggleView.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btn_view_logs -> {
                        binding.rvNotificationsFeed.visibility = View.VISIBLE
                        binding.rvNotificationsStats.visibility = View.GONE
                        binding.spinnerAppFilter.visibility = View.VISIBLE
                        binding.tvFeedContext.visibility = View.VISIBLE
                    }
                    R.id.btn_view_stats -> {
                        binding.rvNotificationsFeed.visibility = View.GONE
                        binding.rvNotificationsStats.visibility = View.VISIBLE
                        binding.spinnerAppFilter.visibility = View.GONE
                        binding.tvFeedContext.visibility = View.GONE
                        binding.tvEmptyState.visibility = View.GONE
                    }
                }
            }
        }

        // SearchView listener
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.setSearchQuery(query ?: "")
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText ?: "")
                return true
            }
        })

        val allAppsLabel = "Semua aplikasi"

        // Observe filter apps spinner
        viewModel.filterApps.observe(viewLifecycleOwner) { apps ->
            val displayApps = apps.map { if (it == "All Apps") allAppsLabel else it }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, displayApps).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            binding.spinnerAppFilter.adapter = adapter

            // Re-apply selected filter
            val currentFilter = viewModel.selectedAppFilter.value ?: "All Apps"
            val displayFilter = if (currentFilter == "All Apps") allAppsLabel else currentFilter
            val index = displayApps.indexOf(displayFilter)
            if (index >= 0) {
                binding.spinnerAppFilter.setSelection(index)
            }
        }

        // Spinner item selected listener
        binding.spinnerAppFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isFirstSpinnerSelection) {
                    isFirstSpinnerSelection = false
                    return
                }
                val selected = parent?.getItemAtPosition(position)?.toString() ?: allAppsLabel
                val app = if (selected == allAppsLabel) "All Apps" else selected
                viewModel.setAppFilter(app)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Observe feed logs list
        viewModel.feedList.observe(viewLifecycleOwner) { logs ->
            feedAdapter.submitList(logs)
            filteredNotificationCount = logs.size
            if (logs.isEmpty() && binding.rvNotificationsFeed.visibility == View.VISIBLE) {
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.tvEmptyState.text = if (storedNotificationCount == 0) "Belum ada catatan notifikasi."
                    else "Tidak ada notifikasi yang cocok dengan pencarian dan filter."
            } else {
                binding.tvEmptyState.visibility = View.GONE
            }
            updateFeedContext()
        }

        viewModel.allLogs.observe(viewLifecycleOwner) { logs ->
            storedNotificationCount = logs.size
            if (feedAdapter.currentList.isEmpty() && binding.rvNotificationsFeed.visibility == View.VISIBLE) {
                binding.tvEmptyState.text = if (storedNotificationCount == 0) "Belum ada catatan notifikasi."
                    else "Tidak ada notifikasi yang cocok dengan pencarian dan filter."
                binding.tvEmptyState.visibility = View.VISIBLE
            }
            updateFeedContext()
        }
        viewModel.searchQuery.observe(viewLifecycleOwner) { query ->
            if (binding.searchView.query.toString() != query) binding.searchView.setQuery(query, false)
            updateFeedContext()
        }
        viewModel.selectedAppFilter.observe(viewLifecycleOwner) { updateFeedContext() }

        // Observe stats
        viewModel.statsList.observe(viewLifecycleOwner) { stats ->
            statsAdapter.submitList(stats)
        }
    }

    private fun updateFeedContext() {
        val query = viewModel.searchQuery.value.orEmpty().trim()
        val app = viewModel.selectedAppFilter.value?.takeIf { it != "All Apps" } ?: "Semua aplikasi"
        val queryLabel = if (query.isEmpty()) "tanpa pencarian" else "pencarian ‘$query’"
        binding.tvFeedContext.text = "$app · $queryLabel · $filteredNotificationCount hasil"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
