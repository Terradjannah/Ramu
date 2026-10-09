package com.assistant.adi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.assistant.adi.data.SecurePreferencesHelper
import com.assistant.adi.data.model.AiModelCatalog
import com.assistant.adi.util.ModelFiles
import com.assistant.adi.util.responseStream
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request

/** Explicit device test: validates the existing model without downloading/replacing it, then runs local inference. */
@RunWith(AndroidJUnit4::class)
class NativeAiRegressionTest {
    @Test fun existingE2bCanGenerateOnCpu()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val model=AiModelCatalog.MODEL_E2B
        val file=File(context.getExternalFilesDir("models"),model.fileName)
        assertTrue("Model E2B lokal belum tersedia",file.exists())
        val token=SecurePreferencesHelper(context).getHfToken().orEmpty()
        val request=Request.Builder().url(model.downloadUrl).header("Authorization","Bearer $token").head().build()
        OkHttpClient().newCall(request).execute().use { response ->
            assertTrue("Verifikasi ukuran server gagal: ${response.code}",response.isSuccessful)
            val total=response.header("Content-Length")?.toLongOrNull()
            assertEquals("Ukuran model lokal berbeda; file tidak diubah",total,file.length())
        }
        ModelFiles.markReady(file)
        withContext(Dispatchers.IO) {
            val engine=Engine(EngineConfig(modelPath=file.absolutePath,backend=Backend.CPU(),maxNumTokens=512))
            try {
                engine.initialize()
                val conversation=engine.createConversation(ConversationConfig(maxOutputToken=12,thinkingConfig=ThinkingConfig(enableThinking=false)))
                try {
                    repeat(2) { turn ->
                        val response=StringBuilder()
                        conversation.responseStream("Reply with the single word READY.").collect { response.append(it.toString()) }
                        // Reaching this assertion requires the terminal callback, not just tokens.
                        assertTrue("Respons ${turn + 1} tidak selesai atau kosong",response.isNotBlank())
                    }
                } finally { conversation.close() }
            } finally { engine.close() }
        }
    }
}
