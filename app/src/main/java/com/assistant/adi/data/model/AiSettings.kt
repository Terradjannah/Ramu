package com.assistant.adi.data.model

data class AiSettings(
    val temperature: Float = 0.3f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 1024,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val userPromptTemplate: String = DEFAULT_USER_PROMPT_TEMPLATE,
    val accelerator: String = "AUTO", // AUTO (NPU -> CPU), CPU, atau GPU
    val enableThinking: Boolean = false,
    val activeModelPath: String = "",
    val activeVariantIdentity: String = "",
    val idleTimeoutMinutes: Int = 3
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Kamu adalah asisten personal Ramu yang tertanam dalam " +
            "aplikasi monitoring dashboard di perangkat Android. Tugasmu adalah " +
            "menganalisis data monitoring perangkat yang diberikan dan " +
            "memberikan penjelasan serta saran yang mudah dilakukan. Selalu " +
            "jawab dalam Bahasa Indonesia. Jadilah ringkas dan praktis. " +
            "Jangan sebut bahwa kamu adalah Gemma atau model AI apapun — " +
            "gunakan nama asisten dari personalisasi. Jika tidak ada data perangkat yang " +
            "diberikan, jawab sebagai asisten umum yang membantu."

        const val DEFAULT_USER_PROMPT_TEMPLATE =
            "[DATA PERANGKAT SAAT INI]\n" +
            "Baterai: {battery_pct}% | Suhu: {battery_temp}°C | " +
            "Status: {charging_status} | Voltase: {battery_voltage}mV\n" +
            "RAM: {ram_used}GB digunakan dari {ram_total}GB tersedia\n" +
            "CPU: {cpu_usage}% | Suhu CPU: {cpu_temp}°C\n" +
            "Screen time hari ini: {screen_time} menit\n" +
            "Koneksi: {network_type} | Kecepatan: {network_speed}\n\n" +
            "Pertanyaan pengguna: {user_message}"
    }
}


