package com.assistant.adi.ui.ai

data class ChatSuggestion(val id: String, val text: String)

object ChatSuggestions {
    val all = listOf(
        ChatSuggestion("panas", "Kenapa HP terasa panas?"),
        ChatSuggestion("hemat", "Bagaimana agar baterai lebih awet?"),
        ChatSuggestion("isi", "Apa yang perlu diperhatikan saat mengisi daya?"),
        ChatSuggestion("suhu", "Apa beda suhu baterai dan suhu CPU?"),
        ChatSuggestion("ram", "Kenapa RAM terpakai meski aplikasi sudah ditutup?"),
        ChatSuggestion("lambat", "Apa yang perlu diperiksa saat HP terasa lambat?"),
        ChatSuggestion("ruang", "Bagaimana memeriksa penyimpanan yang penuh?"),
        ChatSuggestion("kuota", "Bagaimana mengatur target kuota internet?"),
        ChatSuggestion("wifi", "Kenapa Wi-Fi terhubung tetapi internet tidak jalan?"),
        ChatSuggestion("waktu", "Bantu aku mengatur waktu memakai HP."),
        ChatSuggestion("fokus", "Bantu aku membuat jadwal fokus hari ini."),
        ChatSuggestion("jeda", "Beri aku ide kegiatan saat jeda dari layar."),
        ChatSuggestion("izin", "Untuk apa akses penggunaan aplikasi?"),
        ChatSuggestion("grafik", "Bantu aku memahami grafik pemakaian HP."),
        ChatSuggestion("cerita", "Aku ingin cerita tentang hariku.")
    )

    fun choose(random: kotlin.random.Random = kotlin.random.Random.Default): List<ChatSuggestion> =
        all.shuffled(random).take(2)

    fun restore(ids: List<String>): List<ChatSuggestion> = ids.mapNotNull { id -> all.find { it.id == id } }
}
