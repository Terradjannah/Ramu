package com.assistant.adi.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSuggestionsTest {
    @Test fun chooseReturnsThreeDistinctCatalogSuggestions() {
        val selected = ChatSuggestions.choose(kotlin.random.Random(42))

        assertEquals(3, selected.size)
        assertEquals(3, selected.map { it.id }.distinct().size)
        assertTrue(selected.all { it in ChatSuggestions.all })
    }

    @Test fun selectedSuggestionsRestoreInTheirOriginalOrder() {
        val selected = ChatSuggestions.choose(kotlin.random.Random(7))

        assertEquals(selected, ChatSuggestions.restore(selected.map { it.id }))
    }
}
