package com.assistant.adi.data.model

sealed class ModelState {
    /** Initial state before the model file check completes; never triggers a redirect. */
    object Checking : ModelState()
    object NotDownloaded : ModelState()
    object Loading : ModelState()
    data class Ready(val activeModelName: String = "", val backendUsed: String = "CPU") : ModelState()
    data class Standby(val activeModelName: String = "") : ModelState()
    data class Error(val message: String) : ModelState()
}
