package com.assistant.adi.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.assistant.adi.ui.buddy.BuddyUi

/** One honest place to review and open the system settings that Ramu needs. */
class PermissionCenterFragment : Fragment() {
    private var body: LinearLayout? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    private val checklist by lazy { PermissionChecklist(this) { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val page = BuddyUi(requireContext()).page()
        body = page.second
        return page.first
    }

    override fun onViewCreated(view: View, state: Bundle?) { super.onViewCreated(view, state); render() }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val context = context ?: return
        val content = body ?: return
        val ui = BuddyUi(context)
        content.removeAllViews()
        content.addView(ui.heading("Izin aplikasi"), ui.margin(bottom = 4))
        content.addView(ui.text("Periksa izin yang dibutuhkan fitur tertentu. Mengizinkan di sini tidak menyalakan pencatatan atau pengingat secara otomatis.", secondary = true), ui.margin(bottom = 12))
        checklist.render(ui, content)
    }
    override fun onDestroyView() { body = null; super.onDestroyView() }
}
