package com.assistant.adi.ui.buddy

/** Stable character identities shared by profile storage and renderer selection. */
enum class BuddyCharacter(
    val id: String,
    val label: String,
    val rendererId: String = id,
    val availableInProfile: Boolean = false
) {
    CAT("cat", "Kucing (Mochi)"),
    PANDA("panda", "Bao", availableInProfile = true),
    DUCK("duck", "Bebek (Ducky)");

    companion object {
        fun fromId(id: String?): BuddyCharacter =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: CAT
    }
}
