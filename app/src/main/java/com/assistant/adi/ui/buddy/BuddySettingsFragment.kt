package com.assistant.adi.ui.buddy

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.assistant.adi.R
import com.assistant.adi.data.PrefsManager
import com.assistant.adi.ui.DashboardActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class BuddySettingsFragment : Fragment() {
    private var body: LinearLayout? = null
    private var sheet: BottomSheetDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val (scroll, content) = BuddyPageUi(requireContext()).page()
        body = content
        return scroll
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val body = body ?: return
        body.removeAllViews()
        val page = BuddyPageUi(requireContext())
        val ui = page.ui
        val profile = BuddyProfile(requireContext())
        val prefs = PrefsManager(requireContext())

        // --- Kartu Profil (Mint) ---
        val profileCard = page.card(body, R.color.buddy_mint)
        profileCard.addView(page.text("Ramu milik", 14f, secondary = true), ui.margin(bottom = 4))
        profileCard.addView(page.text(profile.userName, 28f, true).apply {
            maxLines = 3
        }, ui.margin(bottom = 4))
        profileCard.addView(page.text("Ditemani ${profile.buddyName}", 16f), ui.margin(bottom = 16))
        profileCard.addView(page.button("Ubah profil", secondary = true, icon = R.drawable.ic_ms_edit) { editProfile() }, ui.margin(bottom = 0))

        // --- Kelompok Keseharian ---
        page.section(body, "Keseharian")
        val preferences = page.card(body).apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4)) }
        val theme = when (prefs.themeMode) { 1 -> "Terang"; 2 -> "Gelap"; else -> "Ikuti sistem" }
        page.row(preferences, "Tampilan & gerakan", "$theme · ${if (prefs.buddyMotion) "Gerakan aktif" else "Gerakan dimatikan"}", R.drawable.ic_settings, R.color.buddy_mint) { editAppearance() }
        page.divider(preferences)
        page.row(preferences, "Pengingat jeda Bao", "${breakLabel(prefs.buddyDailyMinutes)} harian · ${breakLabel(prefs.buddySessionMinutes)} sesi", R.drawable.ic_notifications, R.color.buddy_mint) { editBreakReminders() }
        page.divider(preferences)
        val notifications = NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
        page.row(preferences, "Izin aplikasi", if (notifications) "Periksa akses dan pengingat" else "Ada izin yang perlu diperiksa", R.drawable.ic_notifications, R.color.buddy_mint) { navigate("permissions") }

        // --- Kelompok Asisten & Catatan ---
        page.section(body, "Asisten & catatan")
        val data = page.card(body).apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4)) }
        page.row(data, "Pengaturan AI", "Model dan cara Buddy menjawab", R.drawable.ic_ai_assistant, R.color.buddy_sky) { navigate("ai_settings") }
        page.divider(data)
        page.row(data, "Pencatatan riwayat", if (prefs.monitoringEnabled) "Pencatatan aktif" else "Pencatatan dijeda", R.drawable.ic_stats_chart, R.color.buddy_sky) { navigate("background") }
        page.divider(data)
        page.row(data, "Cadangan & ekspor", "Ekspor catatan atau kelola cadangan lokal", R.drawable.ic_storage, R.color.buddy_sky) { navigate("backup_export") }

        // --- Privasi & Penyimpanan Data (Disclosure + Dialog) ---
        val disclosureCard = page.card(body).apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }
        val privacyDetail = page.text("Nama, percakapan, dan catatan disimpan di perangkat ini. Model AI diunduh lewat internet; jawaban diproses lokal tanpa mengirim percakapan ke server luar. Izin membaca notifikasi terpisah dari izin mengirim pengingat.", 14f, secondary = true).apply { visibility = View.GONE }
        val privacyBtn = page.textButton("Pelajari rincian privasi", icon = R.drawable.ic_ms_info) { showPrivacyDialog() }.apply { visibility = View.GONE }
        val disclosureControl = MaterialButton(ui.context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Tentang penyimpanan data"
            isAllCaps = false
            textSize = 15f
            minHeight = ui.dp(48)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(ui.color(R.color.buddy_ink))
            setIconResource(R.drawable.ic_buddy_chevron)
            iconTint = ColorStateList.valueOf(ui.color(R.color.buddy_sub))
            iconGravity = MaterialButton.ICON_GRAVITY_END
            ViewCompat.setStateDescription(this, "Ditutup")
            setOnClickListener {
                val expanded = privacyDetail.visibility != View.VISIBLE
                privacyDetail.visibility = if (expanded) View.VISIBLE else View.GONE
                privacyBtn.visibility = if (expanded) View.VISIBLE else View.GONE
                setIconResource(if (expanded) R.drawable.ic_buddy_expand else R.drawable.ic_buddy_chevron)
                ViewCompat.setStateDescription(this, if (expanded) "Dibuka" else "Ditutup")
            }
        }
        disclosureCard.addView(disclosureControl, ui.margin(bottom = 0))
        disclosureCard.addView(privacyDetail, ui.margin(top = 4, bottom = 8))
        disclosureCard.addView(privacyBtn, ui.margin(bottom = 4))

        // --- Sambutan ---
        body.addView(page.button("Lihat sambutan lagi", secondary = true, icon = R.drawable.ic_ms_refresh) { navigate("onboarding") }, ui.margin(top = 8))
    }

    private fun editProfile() {
        val page = BuddyPageUi(requireContext())
        val ui = page.ui
        val profile = BuddyProfile(requireContext())
        val dialog = BottomSheetDialog(ui.context)
        sheet = dialog
        val (scroll, body) = ui.page()
        scroll.setBackgroundColor(ui.color(R.color.buddy_tile))

        body.addView(page.text("Profilmu", 24f, true), ui.margin(top = 16, bottom = 8))
        body.addView(page.text("Nama disimpan lokal. Kosongkan untuk memakai Teman dan Buddy.", 14f, secondary = true), ui.margin(bottom = 20))

        val user = page.field(body, "Nama panggilan", profile.userName, 0x504211)
        val buddy = page.field(body, "Nama asisten", profile.buddyName, 0x504212)
        var selectedCharacter = BuddyCharacter.PANDA

        val focusScroller = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                scroll.postDelayed({
                    val rect = android.graphics.Rect()
                    v.getHitRect(rect)
                    scroll.requestChildRectangleOnScreen(v, rect, true)
                }, 200)
            }
        }
        user.onFocusChangeListener = focusScroller
        buddy.onFocusChangeListener = focusScroller

        body.addView(page.text("Cara Buddy berbicara", 17f, true), ui.margin(top = 4, bottom = 4))
        body.addView(page.text("Pilihan ini mengubah ucapan di Beranda dan gaya jawaban AI.", 14f, secondary = true), ui.margin(bottom = 8))
        val styles = RadioGroup(ui.context).apply { orientation = RadioGroup.VERTICAL }
        val radioTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ui.color(R.color.buddy_action), ui.color(R.color.buddy_outline))
        )
        val casual = RadioButton(ui.context).apply {
            id = View.generateViewId()
            text = "Santai & ramah"
            textSize = 15f
            setTextColor(ui.color(R.color.buddy_ink))
            buttonTintList = radioTint
            minHeight = ui.dp(48)
            isChecked = profile.casual
        }
        val calm = RadioButton(ui.context).apply {
            id = View.generateViewId()
            text = "Ringkas & tenang"
            textSize = 15f
            setTextColor(ui.color(R.color.buddy_ink))
            buttonTintList = radioTint
            minHeight = ui.dp(48)
            isChecked = !profile.casual
        }
        styles.addView(casual)
        styles.addView(calm)
        body.addView(styles, ui.margin(bottom = 20))
        body.addView(BuddyCharacterPicker(ui.context, selectedCharacter) { selectedCharacter = it }, ui.margin(bottom = 20))

        body.addView(page.button("Simpan perubahan", icon = R.drawable.ic_ms_save) {
            profile.userName = user.text.toString()
            profile.buddyName = buddy.text.toString()
            profile.casual = casual.isChecked
            PrefsManager(requireContext()).setBuddyCharacter(selectedCharacter.id)
            dialog.dismiss()
            render()
            view?.let { Snackbar.make(it, "Profil disimpan", Snackbar.LENGTH_SHORT).show() }
        }, ui.margin(bottom = 8))

        body.addView(page.button("Batal", secondary = true, icon = R.drawable.ic_ms_close) { dialog.dismiss() }, ui.margin(bottom = 12))

        @Suppress("DEPRECATION")
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(0, 0, 0, maxOf(imeBottom, navBottom))
            insets
        }

        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.show()
    }

    private fun editAppearance() {
        val page = BuddyPageUi(requireContext())
        val ui = page.ui
        val prefs = PrefsManager(requireContext())
        val dialog = BottomSheetDialog(ui.context)
        sheet = dialog
        val (scroll, content) = ui.page()
        scroll.setBackgroundColor(ui.color(R.color.buddy_tile))

        content.addView(page.text("Tampilan & gerakan", 24f, true), ui.margin(top = 16, bottom = 6))
        content.addView(page.text("Atur tema dan gerakan Bao di Beranda.", 14f, secondary = true), ui.margin(bottom = 16))

        content.addView(page.text("Tampilan", 16f, true), ui.margin(bottom = 6))
        val themeModes = intArrayOf(-1, 1, 2)
        val themes = RadioGroup(ui.context).apply { orientation = RadioGroup.VERTICAL }
        val radioTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ui.color(R.color.buddy_action), ui.color(R.color.buddy_outline))
        )
        arrayOf("Ikuti sistem", "Terang", "Gelap").forEachIndexed { index, label ->
            themes.addView(RadioButton(ui.context).apply {
                id = View.generateViewId()
                tag = themeModes[index]
                text = label
                textSize = 15f
                setTextColor(ui.color(R.color.buddy_ink))
                buttonTintList = radioTint
                minHeight = ui.dp(48)
                isChecked = themeModes[index] == prefs.themeMode
            })
        }
        content.addView(themes, ui.margin(bottom = 12))

        val motion = com.google.android.material.switchmaterial.SwitchMaterial(ui.context).apply {
            text = "Gerakan Buddy"
            textSize = 16f
            setTextColor(ui.color(R.color.buddy_ink))
            isChecked = prefs.buddyMotion
            minHeight = ui.dp(56)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ui.color(R.color.buddy_action), ui.color(R.color.buddy_outline))
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ui.color(R.color.buddy_mint), ui.color(R.color.buddy_divider))
            )
        }
        content.addView(motion, ui.margin(bottom = 4))
        content.addView(page.text("Saat dimatikan, ekspresi Bao tetap mengikuti kondisi HP.", 13f, secondary = true), ui.margin(bottom = 16))

        content.addView(page.button("Simpan pilihan", icon = R.drawable.ic_ms_save) {
            val selectedTheme = themes.findViewById<RadioButton>(themes.checkedRadioButtonId)?.tag as? Int ?: prefs.themeMode
            prefs.themeMode = selectedTheme
            prefs.buddyMotion = motion.isChecked
            AppCompatDelegate.setDefaultNightMode(selectedTheme)
            dialog.dismiss()
            render()
            view?.let { Snackbar.make(it, "Tampilan dan gerakan diperbarui", Snackbar.LENGTH_SHORT).show() }
        }, ui.margin(bottom = 8))
        content.addView(page.button("Batal", secondary = true, icon = R.drawable.ic_ms_close) { dialog.dismiss() }, ui.margin(bottom = 12))

        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.show()
    }

    private fun editBreakReminders() {
        val page = BuddyPageUi(requireContext())
        val ui = page.ui
        val prefs = PrefsManager(requireContext())
        val dialog = BottomSheetDialog(ui.context)
        sheet = dialog
        val (scroll, content) = ui.page()
        scroll.setBackgroundColor(ui.color(R.color.buddy_tile))
        content.addView(page.text("Pengingat jeda Bao", 24f, true), ui.margin(top = 16, bottom = 6))
        content.addView(page.text("Pilihan ini mengubah ucapan Bao di Beranda. Aplikasi tidak diblokir.", 14f, secondary = true), ui.margin(bottom = 16))

        val dailyValues = listOf(0, 120, 240, 360, 480)
        val sessionValues = listOf(0, 30, 60, 90)
        fun options(title: String, values: List<Int>, selected: Int): RadioGroup {
            content.addView(page.text(title, 16f, true), ui.margin(bottom = 4))
            return RadioGroup(ui.context).apply {
                orientation = RadioGroup.VERTICAL
                values.forEach { value ->
                    addView(RadioButton(ui.context).apply {
                        id = View.generateViewId()
                        tag = value
                        text = breakLabel(value)
                        textSize = 15f
                        setTextColor(ui.color(R.color.buddy_ink))
                        minHeight = ui.dp(48)
                        isChecked = value == selected
                    })
                }
            }.also { content.addView(it, ui.margin(bottom = 16)) }
        }
        val daily = options("Batas waktu layar harian", dailyValues, prefs.buddyDailyMinutes)
        val session = options("Layar terus digunakan", sessionValues, prefs.buddySessionMinutes)
        content.addView(page.button("Simpan pilihan", icon = R.drawable.ic_ms_save) {
            prefs.buddyDailyMinutes = daily.findViewById<RadioButton>(daily.checkedRadioButtonId)?.tag as? Int ?: prefs.buddyDailyMinutes
            prefs.buddySessionMinutes = session.findViewById<RadioButton>(session.checkedRadioButtonId)?.tag as? Int ?: prefs.buddySessionMinutes
            dialog.dismiss()
            render()
            view?.let { Snackbar.make(it, "Pengingat jeda disimpan", Snackbar.LENGTH_SHORT).show() }
        }, ui.margin(bottom = 8))
        content.addView(page.button("Batal", secondary = true, icon = R.drawable.ic_ms_close) { dialog.dismiss() }, ui.margin(bottom = 12))
        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.show()
    }

    private fun breakLabel(minutes: Int): String = if (minutes == 0) "Tidak perlu" else com.assistant.adi.ui.buddy.screenDuration(minutes)

    private fun showPrivacyDialog() {
        val page = BuddyPageUi(requireContext())
        val ui = page.ui
        val dialog = BottomSheetDialog(ui.context)
        sheet = dialog
        val (scroll, content) = ui.page()
        scroll.setBackgroundColor(ui.color(R.color.buddy_tile))

        content.addView(page.text("Privasi & penyimpanan data", 24f, true), ui.margin(top = 16, bottom = 6))
        content.addView(page.text("Ramu dirancang untuk menjaga kendali privasi sepenuhnya di perangkatmu.", 14f, secondary = true), ui.margin(bottom = 16))

        fun privacyCard(title: String, description: String) {
            val card = page.card(content).apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)) }
            card.addView(page.text(title, 16f, true), ui.margin(bottom = 4))
            card.addView(page.text(description, 14f, secondary = true), ui.margin(bottom = 0))
        }
        privacyCard("Data tetap di perangkat", "Nama, sapaan, percakapan obrolan, dan riwayat kondisi ponsel disimpan di database lokal ponselmu dan tidak diunggah ke server luar.")
        privacyCard("Inferensi AI lokal", "Model AI dijalankan secara offline langsung di prosesor perangkat. Tidak ada pesan atau percakapan yang dikirim ke internet untuk dijawab.")
        privacyCard("Izin transparan", "Akses penggunaan aplikasi dan riwayat notifikasi hanya digunakan untuk statistik lokal dan terpisah sepenuhnya dari izin pengingat.")

        content.addView(page.button("Tutup", icon = R.drawable.ic_ms_close) { dialog.dismiss() }, ui.margin(top = 16, bottom = 12))

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(0, 0, 0, navBottom)
            insets
        }

        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.show()
    }

    private fun navigate(tag: String) {
        (requireActivity() as DashboardActivity).navigateSection(tag)
    }

    override fun onDestroyView() {
        sheet?.dismiss()
        sheet = null
        body = null
        super.onDestroyView()
    }
}
