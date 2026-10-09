package com.assistant.adi

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.adi.data.*
import com.assistant.adi.data.model.*
import com.assistant.adi.ui.ai.AiChatViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real ViewModel/native integration, isolated Room and preferences. Existing model is read only. */
@RunWith(AndroidJUnit4::class)
class ChatSessionRegressionTest {
    @Test fun cancelResumeCompactAndSwitchSessions() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val preferencesName = "chat-test-${System.nanoTime()}"
        val preferences = app.getSharedPreferences(preferencesName, 0)
        val settings = MutableStateFlow(AiSettings(maxTokens = 512, systemPrompt = "Jawab dalam Bahasa Indonesia. Ikuti permintaan pengguna.", userPromptTemplate = "{user_message}"))
        val store = ViewModelStore()
        val vm = AiChatViewModel(app, db.chatMessageDao(), settings, preferences)
        store.put("chat", vm)
        suspend fun terminal(): StreamingState = withTimeout(180_000) {
            vm.streamingState.first { it is StreamingState.Done || it is StreamingState.Error || it is StreamingState.Idle }
        }
        suspend fun readyForNext() { delay(300) }
        try {
            withTimeout(10_000) { vm.modelState.first { it !is ModelState.Checking } }
            readyForNext()
            val sessionId = vm.activeSession.value
            vm.sendMessage("Tuliskan daftar 100 kota dunia, satu kota per baris dengan penjelasan singkat.")
            withTimeout(180_000) { vm.streamingState.first { it is StreamingState.Streaming || it is StreamingState.Error } }
            assertFalse(vm.streamingState.value.toString(), vm.streamingState.value is StreamingState.Error)
            vm.cancelGeneration()
            assertTrue(terminal() is StreamingState.Idle)
            readyForNext()
            assertEquals("cancelled", db.chatMessageDao().messages(sessionId).single().status)

            vm.sendMessage("Jawab dengan satu kata: SIAP.")
            assertTrue(terminal().toString(), vm.streamingState.value is StreamingState.Done)
            readyForNext()
            assertEquals(3, db.chatMessageDao().messages(sessionId).size)
            // Force compaction with a historical turn larger than the configured input budget.
            val dao = db.chatMessageDao()
            val id = dao.insertMessage(ChatMessage(role = "user", content = "Kode proyek adalah MELATI. " + "Catatan lama tentang proyek. ".repeat(900), sessionId = sessionId, status = "pending"))
            dao.completeTurn(id, ChatMessage(role = "ai", content = "Saya ingat kode proyek MELATI.", sessionId = sessionId))
            val rawCount = dao.messages(sessionId).size
            vm.sendMessage("Apa kode proyek yang dibahas tadi?")
            val result = terminal()
            assertTrue(result.toString(), result is StreamingState.Done)
            readyForNext()
            assertTrue(dao.session(sessionId)!!.summary.isNotBlank())
            assertTrue(dao.session(sessionId)!!.summarizedThroughId > 0)
            assertEquals(rawCount + 2, dao.messages(sessionId).size)

            vm.newChat()
            withTimeout(10_000) { vm.activeSession.first { it != sessionId } }
            readyForNext()
            assertNotEquals(sessionId, vm.activeSession.value)
            assertTrue(dao.messages(vm.activeSession.value).isEmpty())
            vm.openChat(sessionId)
            withTimeout(10_000) { vm.activeSession.first { it == sessionId } }
            readyForNext()
            vm.releaseForModelChange() // Simulate a cold mount without deleting persisted context.
            vm.sendMessage("Sebutkan kode proyek tadi, singkat.")
            val restored = terminal()
            assertTrue(restored.toString(), restored is StreamingState.Done)
            assertTrue((restored as StreamingState.Done).fullText.contains("MELATI", true))
            readyForNext()
            vm.releaseForModelChange()
            vm.sendMessage("Uji pembatalan saat model dimuat.")
            withTimeout(30_000) { vm.modelState.first { it is ModelState.Loading } }
            vm.cancelGeneration()
            assertTrue(terminal() is StreamingState.Idle)
            readyForNext()
            assertEquals("cancelled", dao.messages(sessionId).last().status)
            vm.retryLastMessage()
            val retried = terminal()
            assertTrue(retried.toString(), retried is StreamingState.Done)
            readyForNext()
            assertEquals("complete", dao.messages(sessionId).dropLast(1).last().status)
        } finally {
            vm.cancelGeneration()
            vm.releaseForModelChange()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            delay(500)
            db.close()
            app.deleteSharedPreferences(preferencesName)
        }
    }
}
