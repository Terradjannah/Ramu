package com.assistant.adi.ui.buddy.renderer.panda

/** Observations only: velocities are radii/second, impact is supplied by the playground controller. */
class PandaInteractionSignals {
    var pressed = false
    var dragging = false
    var velocityX = 0f
    var velocityY = 0f
    var impact = 0f
    var sampleAge = 0f
    fun reset() {
        pressed = false; dragging = false; velocityX = 0f; velocityY = 0f; impact = 0f; sampleAge = 0f
    }
}
