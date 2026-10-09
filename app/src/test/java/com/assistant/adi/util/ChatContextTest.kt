package com.assistant.adi.util

import com.assistant.adi.data.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class ChatContextTest {
    private fun message(id: Long, role: String, status: String = "complete", text: String = "text") =
        ChatMessage(id = id, role = role, content = text, sessionId = "a", status = status)

    @Test fun onlyCompletePairsAreReplayedAfterCancellationOrProcessDeath() {
        val history = listOf(message(1, "user", "cancelled"), message(2, "user"), message(3, "ai"),
            message(4, "user", "interrupted"), message(5, "user", "failed"), message(6, "user"), message(7, "ai"))
        assertEquals(listOf(2L, 3L, 6L, 7L), ChatContext.completedTurns(history).flatten().map { it.id })
    }

    @Test fun oversizedUnicodeHistoryIsSplitWithoutLosingOrBreakingCharacters() {
        val original = "👩🏽‍💻 日本語 baterai ".repeat(500)
        val chunks = ChatContext.chunks(original, 99)
        assertEquals(original, chunks.joinToString(""))
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 99 })
        assertTrue(chunks.all { !Character.isLowSurrogate(it.first()) && !Character.isHighSurrogate(it.last()) })
    }

    @Test fun outputAndSummarySpaceAreReservedBeforeAcceptingPrompt() {
        val budget = ChatContext.inputBudget("system", "prompt", 2048)
        assertEquals(ChatContext.WINDOW - ChatContext.MARGIN - 2048 - ChatContext.cost("system") - ChatContext.cost("prompt"), budget)
        assertThrows(IllegalArgumentException::class.java) { ChatContext.inputBudget("system", "x".repeat(30000), 2048) }
    }

    @Test fun persistedDeviceContextIsUsedInsteadOfCurrentTelemetry() {
        val message = message(1, "user", text = "Bagaimana baterai hp saya?").copy(contextContent = "Battery at that time: 50%")
        assertEquals("Battery at that time: 50%", ChatContext.content(message))
        assertEquals("Bagaimana baterai hp saya?", ChatContext.content(message.copy(contextContent = "")))
    }

    @Test fun compactionAccountingIncludesSummaryWrappersAndTurnRoles() {
        val turns = listOf(listOf(message(1, "user"), message(2, "ai")))
        assertTrue(ChatContext.historyCost("summary", turns) > ChatContext.historyCost("", turns))
        assertEquals(2 * ChatContext.cost("text"), ChatContext.historyCost("", turns))
    }
}
