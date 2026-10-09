package com.assistant.adi.util

object NotificationPrivacy {
    private val keywords=Regex("(?i)\\b(otp|kode|code|pin|verification|verifikasi|rahasia|secret|password|sandi|passcode|one.time)\\b")
    private val numbers=Regex("(?<![\\p{L}\\p{N}])(?:\\d[ -]?){3,7}\\d(?![\\p{L}\\p{N}])")
    private val sensitive=listOf("bca","mandiri","brimo","bri.mobile","authenticator","authy")
    fun sanitize(packageName:String,title:String,text:String,saveContent:Boolean):Pair<String,String> {
        if(!saveContent) return "Notifikasi diterima" to "Konten tidak disimpan"
        if(sensitive.any { packageName.contains(it,true) } || keywords.containsMatchIn("$title $text"))
            return "[KONTEN_SENSITIF_DIBLOKIR]" to "[KONTEN_SENSITIF_DIBLOKIR]"
        return title.replace(numbers,"[REDACTED]") to text.replace(numbers,"[REDACTED]")
    }
}
