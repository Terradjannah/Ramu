package com.assistant.adi.ui
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.assistant.adi.R
import com.assistant.adi.MainApplication
import com.assistant.adi.databinding.ActivityDashboardBinding
import com.assistant.adi.ui.ai.*

class DashboardActivity:AppCompatActivity() {
    private lateinit var binding:ActivityDashboardBinding
    private var playgroundDestination = false
    companion object {
        const val EXTRA_CHAT_ENTRY_ID = "chat_entry_id"
        const val EXTRA_CHAT_ENTRY_MODE = "chat_entry_mode"
        const val CHAT_ENTRY_NEW = "new"
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        binding=ActivityDashboardBinding.inflate(layoutInflater); setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_ms_arrow_back)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _,insets ->
            if (playgroundDestination) {
                binding.root.setPadding(0, 0, 0, 0)
            } else {
                val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
                binding.root.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime.bottom))
            }
            insets
        }
        WindowCompat.getInsetsController(window,binding.root).isAppearanceLightStatusBars=
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        supportFragmentManager.registerFragmentLifecycleCallbacks(object:FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(fm:FragmentManager,f:Fragment) { if(f.id==R.id.fragment_container) updateDestination(f.tag.orEmpty()) }
        },false)
        supportFragmentManager.addOnBackStackChangedListener { updateDestination(supportFragmentManager.findFragmentById(R.id.fragment_container)?.tag.orEmpty()) }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if(supportFragmentManager.backStackEntryCount>0) supportFragmentManager.popBackStack()
                else if(supportFragmentManager.findFragmentById(R.id.fragment_container)?.tag!="hyperos_dashboard") tab("hyperos_dashboard")
                else { isEnabled=false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        if(savedInstanceState==null) navigateSection(if (com.assistant.adi.ui.buddy.BuddyProfile(this).completed) intent.getStringExtra("section") ?: "hyperos_dashboard" else "onboarding",false)
        MainApplication.startMonitoringService(this)
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("section")?.let { navigateSection(it) }
        enforceOnboardingGate()
    }
    override fun onPostResume() { super.onPostResume(); enforceOnboardingGate() }
    override fun onStart() {
        super.onStart()
        MainApplication.setDashboardVisible(this, true)
        com.assistant.adi.worker.WatchdogWorker.sampleNow(this)
    }
    override fun onStop() { MainApplication.setDashboardVisible(this, false); super.onStop() }
    private fun tab(tag:String) {
        if (!com.assistant.adi.ui.buddy.BuddyProfile(this).completed && tag != "onboarding") {
            enforceOnboardingGate()
            return
        }
        if(supportFragmentManager.isStateSaved) return
        if(supportFragmentManager.findFragmentById(R.id.fragment_container)?.tag==tag) return
        supportFragmentManager.popBackStackImmediate(null,FragmentManager.POP_BACK_STACK_INCLUSIVE)
        navigateSection(tag,false)
    }
    fun openHome() {
        if (!com.assistant.adi.ui.buddy.BuddyProfile(this).completed) {
            enforceOnboardingGate()
            return
        }
        tab("hyperos_dashboard")
    }
    fun navigateSection(tag:String,detail:Boolean=true) {
        if (!com.assistant.adi.ui.buddy.BuddyProfile(this).completed && tag != "onboarding") {
            enforceOnboardingGate()
            return
        }
        val destination = when (tag) {
            "monitor_battery", "monitor_temperature", "battery" -> "battery"
            "monitor_memory", "ram" -> "ram"
            "monitor_network", "network" -> "network"
            "monitor_storage" -> "storage"
            else -> tag
        }
        if (tag == "monitor_temperature" && supportFragmentManager.findFragmentById(R.id.fragment_container)?.tag == "battery") {
            val fragment = BatteryFragment().apply { arguments = Bundle().apply { putString("series", "temperature") } }
            updateDestination("battery")
            supportFragmentManager.beginTransaction().setReorderingAllowed(true)
                .replace(R.id.fragment_container, fragment, "battery").commit()
            return
        }
        val fragment=when(destination) {
            "onboarding" -> com.assistant.adi.ui.buddy.OnboardingFragment()
            "battery" -> BatteryFragment()
            "ram" -> RamCpuFragment()
            "network" -> NetworkFragment()
            "storage" -> StorageFragment()
            "screen" -> ScreenTimeFragment()
            "sensors" -> SensorFragment()
            "notifications" -> NotificationLogFragment()
            "settings" -> com.assistant.adi.ui.buddy.BuddySettingsFragment()
            "playground" -> com.assistant.adi.ui.buddy.BuddyPlaygroundFragment()
            "backup_export" -> SettingsFragment()
            "background" -> BackgroundSetupFragment()
            "permissions" -> PermissionCenterFragment()
            "internet_usage" -> InternetUsageFragment()
            "ai_chat", "ai_assistant" -> AiChatFragment().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_CHAT_ENTRY_ID, java.util.UUID.randomUUID().toString())
                    putString(EXTRA_CHAT_ENTRY_MODE, CHAT_ENTRY_NEW)
                }
            }
            "ai_settings" -> AiSettingsFragment()
            "model_gallery", "model_download" -> ModelGalleryFragment()
            else -> HyperOsDashboardFragment()
        }
        if (tag == "monitor_temperature") fragment.arguments = Bundle().apply { putString("series", "temperature") }
        navigate(fragment,destination,detail, force = destination == "ai_chat" || destination == "ai_assistant")
    }
    fun navigate(fragment:Fragment,tag:String,detail:Boolean=true, force:Boolean=false) {
        if (!com.assistant.adi.ui.buddy.BuddyProfile(this).completed && tag != "onboarding") {
            enforceOnboardingGate()
            return
        }
        if(supportFragmentManager.isStateSaved || (!force && supportFragmentManager.findFragmentById(R.id.fragment_container)?.tag==tag)) return
        updateDestination(tag)
        val tx=supportFragmentManager.beginTransaction().setReorderingAllowed(true).setTransition(androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_FADE).replace(R.id.fragment_container,fragment,tag)
        if(detail) tx.addToBackStack(tag)
        tx.commit()
    }
    fun returnToChatOrOpenNew() {
        val fm = supportFragmentManager
        val currentTag = fm.findFragmentById(R.id.fragment_container)?.tag
        if (currentTag == "ai_chat" || currentTag == "ai_assistant") return
        val chatEntry = (fm.backStackEntryCount - 1 downTo 0)
            .firstOrNull { fm.getBackStackEntryAt(it).name == "ai_chat" || fm.getBackStackEntryAt(it).name == "ai_assistant" }
        if (chatEntry != null && !fm.isStateSaved) {
            fm.popBackStackImmediate(fm.getBackStackEntryAt(chatEntry).name, 0)
        } else {
            navigateSection("ai_chat")
        }
    }
    private fun enforceOnboardingGate() {
        if (com.assistant.adi.ui.buddy.BuddyProfile(this).completed || supportFragmentManager.isStateSaved) return
        supportFragmentManager.executePendingTransactions()
        if (supportFragmentManager.findFragmentById(R.id.fragment_container)?.tag == "onboarding") return
        supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        navigateSection("onboarding", detail = false)
    }
    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
    private fun updateDestination(tag:String) {
        playgroundDestination = tag == "playground"
        val home = tag == "hyperos_dashboard"
        val chatDestination = tag == "ai_chat" || tag == "ai_assistant"
        binding.toolbar.visibility = if (home || tag == "onboarding" || playgroundDestination || chatDestination) View.GONE else View.VISIBLE
        supportActionBar?.setDisplayHomeAsUpEnabled(!home && tag != "onboarding" && !chatDestination)
        binding.root.setBackgroundColor(ContextCompat.getColor(this, R.color.buddy_stage))
        binding.toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.buddy_stage))
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.buddy_stage_ink))
        binding.toolbar.navigationIcon?.setTint(ContextCompat.getColor(this, R.color.buddy_stage_ink))
        binding.toolbar.overflowIcon?.setTint(ContextCompat.getColor(this, R.color.buddy_stage_ink))
        val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
            if (playgroundDestination) {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
        ViewCompat.requestApplyInsets(binding.root)
        supportActionBar?.title=when(tag) {
            "battery" -> "Baterai"
            "ram" -> "RAM"
            "storage" -> "Penyimpanan"
            "network" -> "Jaringan"
            "sensors" -> "Sensor"
            "screen" -> "Waktu layar"
            "notifications" -> "Log notifikasi"
            "settings" -> "Pengaturan"
            "playground" -> "Taman bermain"
            "backup_export" -> "Cadangan & ekspor"
            "background" -> "Pencatatan riwayat"
            "permissions" -> "Izin aplikasi"
            "internet_usage" -> "Penggunaan internet"
            "ai_chat" -> com.assistant.adi.ui.buddy.BuddyProfile(this).buddyName
            "ai_settings" -> "Pengaturan AI"
            "onboarding" -> "Ramu"
            "model_gallery","model_download" -> "Model lokal"
            else -> if (tag.startsWith("monitor_")) "Detail perangkat" else "Beranda"
        }
    }
}




