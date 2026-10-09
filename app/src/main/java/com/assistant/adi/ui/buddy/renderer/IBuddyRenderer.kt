package com.assistant.adi.ui.buddy.renderer

import android.graphics.Canvas
import com.assistant.adi.ui.buddy.BuddyExpression
import com.assistant.adi.ui.buddy.BuddyMood

/** Rendering strategy for one buddy character. */
interface IBuddyRenderer {
    val characterId: String

    /**
     * Drawn inside an already transformed local matrix: translated to the pivot,
     * rotated by tilt, and scaled for squash-stretch. Organs are positioned
     * relative to the pivot at (0, 0).
     */
    fun draw(
        canvas: Canvas,
        radius: Float,
        mood: BuddyMood,
        transitionProgress: Float,
        previousMood: BuddyMood,
        elapsedSeconds: Float,
        isBlinking: Boolean,
        blinkAmount: Float,
        gazeX: Float,
        gazeY: Float,
        isPet: Boolean,
        mouthOpenFood: Float,
        expression: BuddyExpression,
        expressionProgress: Float
    )

    /** Clean rest pose with no breathing or particles; used when motion is off. */
    fun drawStillFrame(canvas: Canvas, radius: Float, mood: BuddyMood, expression: BuddyExpression)
}
