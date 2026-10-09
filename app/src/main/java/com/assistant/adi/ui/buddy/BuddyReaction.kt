package com.assistant.adi.ui.buddy

enum class BuddyMood { HAPPY, HOT, LOW_POWER, CONFUSED, OVERWHELMED, OFFLINE, CHARGING, CURIOUS, POUT }

data class BuddyReaction(
    val mood: BuddyMood, val reason: String, val speech: String,
    val destination: String, val priority: Int, val intensity: Float = 0.5f
)

data class BuddyInternetUsageLimit(
    val dailyReached: Boolean = false,
    val cycleReached: Boolean = false,
    val sampledAt: Long? = null,
    val dayStartMillis: Long? = null,
    val dayEndMillis: Long? = null,
    val cycleStartMillis: Long? = null,
    val cycleEndMillis: Long? = null,
    val dataAvailable: Boolean = false
) {
    fun activeReason(now: Long): Reason? {
        val sample = sampledAt ?: return null
        if (!dataAvailable) return null
        val cycleStart = cycleStartMillis
        val cycleEnd = cycleEndMillis
        if (cycleReached && cycleStart != null && cycleEnd != null &&
            sample in cycleStart until cycleEnd && now in cycleStart until cycleEnd
        ) return Reason.CYCLE
        val dayStart = dayStartMillis
        val dayEnd = dayEndMillis
        if (dailyReached && dayStart != null && dayEnd != null &&
            sample in dayStart until dayEnd && now in dayStart until dayEnd
        ) return Reason.DAILY
        return null
    }

    enum class Reason { DAILY, CYCLE }
}

/** Keep renderer inputs stable when readings hover around a boundary. No AI inference is needed. */
class BuddyReactionEngine {
    private var current: BuddyReaction? = null
    private var pendingReason = ""
    private var pendingSince = 0L

    fun update(device: DeviceSnapshot, awareness: BuddyAwareness?, casual: Boolean, now: Long): BuddyReaction =
        update(device, awareness, null, casual, now, System.currentTimeMillis())

    fun update(
        device: DeviceSnapshot,
        awareness: BuddyAwareness?,
        internetLimit: BuddyInternetUsageLimit?,
        casual: Boolean,
        nowElapsed: Long,
        nowWallClock: Long
    ): BuddyReaction {
        val next = choose(device, awareness, internetLimit, casual, nowWallClock)
        val chargingTransition = next.reason == "charging" || current?.reason == "charging"
        if (current == null || next.priority >= 90 || next.reason == current?.reason || chargingTransition) {
            current = next; pendingReason = ""
        } else {
            if (pendingReason != next.reason) { pendingReason = next.reason; pendingSince = nowElapsed }
            if (nowElapsed - pendingSince >= 10_000) { current = next; pendingReason = "" }
        }
        return current!!
    }

    private fun choose(
        device: DeviceSnapshot,
        awareness: BuddyAwareness?,
        internetLimit: BuddyInternetUsageLimit?,
        casual: Boolean,
        nowWallClock: Long
    ): BuddyReaction {
        val readings = device.readings.associateBy { it.monitor }
        val temp = readings[Monitor.TEMPERATURE]?.rawValue
        val battery = readings[Monitor.BATTERY]
        val storage = readings[Monitor.STORAGE]?.percent
        val memory = readings[Monitor.MEMORY]
        val network = readings[Monitor.NETWORK]
        val previous = current?.reason.orEmpty()
        fun reaction(mood: BuddyMood, reason: String, playful: String, calm: String, destination: String, priority: Int, intensity: Float = .5f) =
            BuddyReaction(mood, reason, if (casual) playful else calm, destination, priority, intensity)
        if (temp != null && temp >= if (previous == "battery_hot") 42 else 45)
            return reaction(BuddyMood.HOT, "battery_hot", "Gerah banget. Jeda dulu, yuk?", "Suhu baterai tinggi. Istirahatkan HP.", "monitor_temperature", 100, 1f)
        if (battery?.charging == true && temp != null && temp >= if (previous == "battery_warm") 38 else 40)
            return reaction(BuddyMood.HOT, "battery_warm", "Mulai gerah, nih. Lagi sibuk, ya?", "Suhu baterai mulai meningkat.", "monitor_temperature", 85)
        if (battery?.charging == false && battery.percent != null && battery.percent <= if (previous == "battery_critical") 13 else 10)
            return reaction(BuddyMood.LOW_POWER, "battery_critical", "Tenagaku tipis. Cari charger, yuk.", "Daya hampir habis. Siapkan pengisi daya.", "monitor_battery", 95, 1f)
        if (storage != null && storage >= if (previous == "storage_full") 92 else 95)
            return reaction(BuddyMood.OVERWHELMED, "storage_full", "Ruangku sesak. Rapikan sedikit, yuk?", "Penyimpanan hampir penuh.", "monitor_storage", 90, 1f)
        if (temp != null && (temp >= if (previous == "battery_warm") 38 else 40))
            return reaction(BuddyMood.HOT, "battery_warm", "Mulai gerah, nih. Lagi sibuk, ya?", "Suhu baterai mulai meningkat.", "monitor_temperature", 85)
        if (temp != null && temp < 0)
            return reaction(BuddyMood.CURIOUS, "battery_cold", "Baterainya kedinginan, nih.", "Suhu baterai di bawah 0 °C.", "monitor_temperature", 84)
        if (memory?.condition == Condition.ATTENTION)
            return reaction(BuddyMood.OVERWHELMED, "memory_pressure", "Lagi banyak yang dipikirkan, nih.", "Android melaporkan memori rendah.", "monitor_memory", 80)
        if (battery?.charging == false && battery.percent != null && battery.percent <= if (previous == "battery_low") 25 else 20)
            return reaction(BuddyMood.LOW_POWER, "battery_low", "Mulai lapar. Charger-nya di mana?", "Daya baterai mulai menipis.", "monitor_battery", 75)
        if (storage != null && storage >= if (previous == "storage_tight") 82 else 85)
            return reaction(BuddyMood.OVERWHELMED, "storage_tight", "Tas kita mulai penuh, nih.", "Ruang penyimpanan mulai terbatas.", "monitor_storage", 70)
        when (internetLimit?.activeReason(nowWallClock)) {
            BuddyInternetUsageLimit.Reason.CYCLE ->
                return reaction(BuddyMood.POUT, "internet_cycle_limit", "Jatah data siklus ini sudah habis. Yuk, cek rinciannya.", "Batas data siklus telah tercapai.", "internet_usage", 65)
            BuddyInternetUsageLimit.Reason.DAILY ->
                return reaction(BuddyMood.POUT, "internet_daily_limit", "Target data hari ini sudah tercapai. Yuk, cek rinciannya.", "Batas data harian telah tercapai.", "internet_usage", 65)
            null -> Unit
        }
        if (battery?.charging == true)
            return reaction(BuddyMood.CHARGING, "charging", "Lagi isi tenaga. Temani sebentar, ya.", "Baterai sedang mengisi daya.", "monitor_battery", 20)
        val aware = awareness?.takeIf { System.currentTimeMillis() - it.timestamp in 0..90_000 }
        if (aware?.appLimitReached == true)
            return reaction(BuddyMood.CONFUSED, "app_limit", "Ada aplikasi yang sudah sampai batasmu.", "Batas waktu salah satu aplikasi tercapai.", "screen", 65)
        if (aware?.sessionMinutes != null && aware.sessionGoalMinutes > 0 && aware.sessionMinutes >= aware.sessionGoalMinutes)
            return reaction(BuddyMood.CONFUSED, "long_session", "Ga bosen ketemu aku mulu? Jeda, yuk.", "Waktu layar tanpa jeda sudah mencapai pilihanmu.", "screen", 60)
        if (aware?.todayMinutes != null && aware.dailyGoalMinutes > 0 && aware.todayMinutes >= aware.dailyGoalMinutes)
            return reaction(BuddyMood.CONFUSED, "daily_goal", "Hari ini sudah lama bareng layar, nih.", "Target waktu layar harianmu tercapai.", "screen", 55)
        if (aware?.recentNotifications != null && aware.recentNotifications >= 15)
            return reaction(BuddyMood.OVERWHELMED, "notifications_busy", "Ramai yang mampir. Mau lihat catatannya?", "Banyak notifikasi tercatat dalam 10 menit terakhir.", "notifications", 50)
        if (network?.condition == Condition.ATTENTION)
            return reaction(BuddyMood.OFFLINE, "network_unavailable", "Koneksi belum pasti. Aku tetap di sini.", "Internet belum terverifikasi. Chat lokal tetap tersedia.", "monitor_network", 30)
        if (device.readings.any { it.condition == Condition.UNKNOWN })
            return reaction(BuddyMood.CURIOUS, "data_missing", "Ada kabar HP yang belum terbaca.", "Sebagian data perangkat belum tersedia.", if (awareness?.usageGranted == false) "permissions" else "", 10)
        return reaction(BuddyMood.HAPPY, "comfortable", "HP-nya nyaman. Kamu gimana?", "Bacaan perangkat saat ini normal.", "", 0, .25f)
    }
}
