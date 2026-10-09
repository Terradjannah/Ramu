package com.assistant.adi.util

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * Avoid LiteRT-LM 0.16.1's precompiled Flow adapter: its onDone references
 * SendChannel.close$default with an incompatible binary signature.
 * Callers retain ownership of native cancellation and must wait for its terminal
 * callback before closing the conversation/engine (see AiChatViewModel).
 */
fun Conversation.responseStream(prompt: String): Flow<Message> = aiResponseStream { callback ->
    sendMessageAsync(prompt, callback)
}

internal fun aiResponseStream(start: (MessageCallback) -> Unit): Flow<Message> = callbackFlow {
    start(object : MessageCallback {
        override fun onMessage(message: Message) {
            // JNI callbacks cannot suspend. The unlimited buffer preserves bursts
            // while the consumer updates UI; generation is bounded by maxOutputToken.
            trySend(message)
        }

        override fun onDone() {
            // Explicit cause avoids the SDK's failing default-argument bridge.
            close(null)
        }

        override fun onError(throwable: Throwable) {
            close(throwable)
        }
    })
    awaitClose { /* Native lifecycle and cancellation belong to the caller. */ }
}.buffer(Channel.UNLIMITED)
