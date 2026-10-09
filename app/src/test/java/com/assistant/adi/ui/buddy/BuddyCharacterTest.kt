package com.assistant.adi.ui.buddy

import org.junit.Assert.assertEquals
import org.junit.Test

class BuddyCharacterTest {
    @Test
    fun fromId_keepsExistingPersistedIdsCompatible() {
        assertEquals(BuddyCharacter.CAT, BuddyCharacter.fromId("cat"))
        assertEquals(BuddyCharacter.PANDA, BuddyCharacter.fromId("panda"))
        assertEquals(BuddyCharacter.DUCK, BuddyCharacter.fromId("duck"))
    }

    @Test
    fun fromId_fallsBackToCatForMissingOrUnknownIds() {
        assertEquals(BuddyCharacter.CAT, BuddyCharacter.fromId(null))
        assertEquals(BuddyCharacter.CAT, BuddyCharacter.fromId("unknown"))
    }
}
