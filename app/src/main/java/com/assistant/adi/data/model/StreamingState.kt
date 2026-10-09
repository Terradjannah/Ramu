package com.assistant.adi.data.model

sealed class StreamingState {
    object Idle : StreamingState()
    object Thinking : StreamingState()
    data class Streaming(val partialText: String) : StreamingState()
    data class Done(val fullText: String) : StreamingState()
    data class Error(val message: String) : StreamingState()
}
