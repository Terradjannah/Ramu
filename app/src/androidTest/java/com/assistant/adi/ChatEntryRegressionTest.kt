package com.assistant.adi

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.adi.data.AppDatabase
import com.assistant.adi.data.ChatSession
import com.assistant.adi.data.model.AiSettings
import com.assistant.adi.ui.DashboardActivity
import com.assistant.adi.ui.ai.AiChatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatEntryRegressionTest {
    @Test fun explicitEntriesAreUniqueAndRestoredFragmentKeepsItsEntry() {
        ActivityScenario.launch(DashboardActivity::class.java).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            scenario.onActivity { it.navigateSection("ai_chat") }
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            var firstEntry = ""
            scenario.onActivity {
                firstEntry = it.supportFragmentManager.findFragmentById(R.id.fragment_container)
                    ?.arguments?.getString(DashboardActivity.EXTRA_CHAT_ENTRY_ID).orEmpty()
            }
            assertTrue(firstEntry.isNotBlank())

            scenario.recreate()
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            scenario.onActivity {
                val restored = it.supportFragmentManager.findFragmentById(R.id.fragment_container)
                assertEquals(firstEntry, restored?.arguments?.getString(DashboardActivity.EXTRA_CHAT_ENTRY_ID))
            }

            scenario.onActivity { it.navigateSection("ai_assistant") }
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            scenario.onActivity {
                val nextEntry = it.supportFragmentManager.findFragmentById(R.id.fragment_container)
                    ?.arguments?.getString(DashboardActivity.EXTRA_CHAT_ENTRY_ID)
                assertNotEquals(firstEntry, nextEntry)
            }
        }
    }

    @Test fun newEntrySelectsDeterministicEmptySessionWithoutCreatingRoomRow() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val preferencesName = "entry-test-${System.nanoTime()}"
        val preferences = app.getSharedPreferences(preferencesName, 0)
        val store = ViewModelStore()
        val prior = "previous-chat"
        db.chatMessageDao().insertSession(ChatSession(prior, "Riwayat lama"))
        preferences.edit().putString("active", prior).commit()
        val vm = AiChatViewModel(app, db.chatMessageDao(), MutableStateFlow(AiSettings()), preferences)
        store.put("chat", vm)
        try {
            val entryId = "intent-entry-unique"
            val expected = java.util.UUID.nameUUIDFromBytes(entryId.toByteArray(Charsets.UTF_8)).toString()
            val opened = withTimeout(10_000) { vm.beginNewEntry(entryId).await() }
            assertEquals(expected, opened)
            assertEquals(expected, vm.activeSession.value)
            assertTrue(db.chatMessageDao().messages(expected).isEmpty())
            assertNull(db.chatMessageDao().session(expected))
            assertEquals("Riwayat lama", db.chatMessageDao().session(prior)?.title)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            db.close()
            app.deleteSharedPreferences(preferencesName)
        }
    }
}
