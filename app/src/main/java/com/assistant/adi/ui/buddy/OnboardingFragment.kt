package com.assistant.adi.ui.buddy

import android.os.Bundle
import android.text.InputFilter
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.assistant.adi.R
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.ui.DashboardActivity
import com.assistant.adi.ui.PermissionChecklist
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class OnboardingFragment : Fragment() {
    private var step = 0
    private var user = ""
    private var buddy = "Buddy"
    private var selectedCharacter = BuddyCharacter.PANDA
    private var userInput: TextInputEditText? = null
    private var buddyInput: TextInputEditText? = null
    private var body: LinearLayout? = null
    private lateinit var ui: BuddyUi

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (isAdded) render()
    }
    private val permissionChecklist by lazy {
        PermissionChecklist(this) { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val profile = BuddyProfile(requireContext())
        step = (state?.getInt("step") ?: 0).coerceIn(0, 2)
        user = state?.getString("user") ?: if (profile.completed) profile.userName else ""
        buddy = state?.getString("buddy") ?: profile.buddyName
        selectedCharacter = state?.getString("selectedCharacter")?.let(BuddyCharacter::fromId)
            ?.takeIf(BuddyCharacter::availableInProfile) ?: BuddyCharacter.PANDA
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        ui = BuddyUi(requireContext())
        val (scroll, content) = ui.page()
        body = content
        return scroll
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (step == 2) render()
    }

    private fun remember() {
        userInput?.let { user = it.text?.toString().orEmpty() }
        buddyInput?.let { buddy = it.text?.toString().orEmpty() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        remember()
        outState.putInt("step", step)
        outState.putString("user", user)
        outState.putString("buddy", buddy)
        outState.putString("selectedCharacter", selectedCharacter.id)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val content = body ?: return
        content.removeAllViews()
        userInput = null
        buddyInput = null
        when (step) {
            0 -> renderWelcome(content)
            1 -> renderIntroduction(content)
            2 -> renderPermissions(content)
        }
        val primaryLabel = when (step) { 0 -> "Kenalan dulu"; 1 -> "Lanjut"; else -> "Selesai" }
        content.addView(ui.button(primaryLabel, icon = if (step == 2) R.drawable.ic_ms_check else R.drawable.ic_ms_chevron_right) {
            remember()
            hideKeyboard()
            when (step) {
                0 -> { step = 1; render() }
                1 -> continueFromIntroduction()
                else -> finish()
            }
        }, ui.margin(top = 16, bottom = 4))
        if (step > 0) content.addView(ui.textButton("Kembali", stage = true, icon = R.drawable.ic_ms_arrow_back) { remember(); goBack() }, ui.margin(bottom = 0))
    }

    private fun renderWelcome(content: LinearLayout) {
        content.addView(ui.text("Ramu", 18f, true), ui.margin(top = 12, bottom = 16))
        val height = (resources.configuration.screenHeightDp * 0.33f).toInt().coerceIn(150, 250)
        content.addView(WelcomeDeviceView(requireContext()), LinearLayout.LayoutParams(-1, ui.dp(height)))
        content.addView(ui.text("Kenali HP-mu.\nTanpa istilah rumit.", 30f, true), ui.margin(top = 24, bottom = 12))
        content.addView(ui.text("Lihat kondisinya, lalu tanyakan yang bikin penasaran.", 16f, secondary = true), ui.margin(bottom = 24))
    }

    private fun renderIntroduction(content: LinearLayout) {
        content.addView(ui.text("Perkenalan", 13f, secondary = true), ui.margin(top = 16, bottom = 8))
        content.addView(ui.heading("Biar lebih akrab."), ui.margin(bottom = 8))
        content.addView(ui.text("Nama ini dipakai saat Buddy menyapamu.", 15f, secondary = true), ui.margin(bottom = 24))
        userInput = ui.field(content, "Nama panggilanmu", user, 0x504201).apply {
            filters = arrayOf<InputFilter>(*filters, InputFilter { source, start, end, _, _, _ ->
                source.subSequence(start, end).filterNot(Char::isISOControl)
            })
            setOnEditorActionListener { _, _, _ -> continueFromIntroduction(); hideKeyboard(); true }
        }
        buddyInput = ui.field(content, "Nama asistenmu", buddy, 0x504202)
        content.addView(ui.text("Kamu bisa mengganti nama Buddy nanti.", 13f, secondary = true), ui.margin())
        val picker = BuddyCharacterPicker(requireContext(), selectedCharacter) { selectedCharacter = it }
        selectedCharacter = picker.selectedCharacter()
        content.addView(picker, ui.margin(top = 16, bottom = 0))
    }

    private fun renderPermissions(content: LinearLayout) {
        content.addView(ui.text("Izin aplikasi", 13f, secondary = true), ui.margin(top = 16, bottom = 8))
        content.addView(ui.heading("Pilih akses yang kamu perlukan."), ui.margin(bottom = 8))
        content.addView(ui.text("Semua izin ini opsional. Fitur tertentu terbatas jika aksesnya belum aktif.", 15f, secondary = true), ui.margin(bottom = 12))
        permissionChecklist.render(ui, content, compact = true)
    }

    private fun continueFromIntroduction() {
        remember()
        val cleanName = user.filterNot(Char::isISOControl).trim()
        val input = userInput ?: return
        val inputLayout = input.parent as? TextInputLayout
        if (cleanName.isEmpty() || cleanName.length > 30) {
            inputLayout?.error = "Isi nama panggilan (1–30 karakter)."
            input.requestFocus()
            input.setSelection(input.text?.length ?: 0)
            return
        }
        inputLayout?.error = null
        user = cleanName
        buddy = buddy.filterNot(Char::isISOControl).trim().take(30).ifBlank { "Buddy" }
        step = 2
        render()
    }

    private fun goBack() {
        if (step > 0) {
            remember()
            hideKeyboard()
            step--
            render()
        } else if (BuddyProfile(requireContext()).completed) {
            if (parentFragmentManager.backStackEntryCount > 0) parentFragmentManager.popBackStack()
            else (requireActivity() as DashboardActivity).openHome()
        } else requireActivity().finish()
    }

    private fun hideKeyboard() {
        (requireActivity().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view?.windowToken, 0)
    }

    private fun finish() {
        val profile = BuddyProfile(requireContext())
        profile.userName = user
        profile.buddyName = buddy
        profile.completed = true
        PrefsManager(requireContext()).setBuddyCharacter(selectedCharacter.id)
        hideKeyboard()
        (requireActivity() as DashboardActivity).openHome()
    }

    override fun onDestroyView() {
        remember()
        userInput = null
        buddyInput = null
        body = null
        super.onDestroyView()
    }
}
