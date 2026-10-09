package com.assistant.adi.ui.buddy.renderer.panda

/** Nominal units, degrees and seconds. Feet rest at y=100; head pivot is y=18. */
object PandaAnimParams {
    const val NOMINAL_RADIUS = 100f
    const val GROUND_Y = 100f
    const val HEAD_PIVOT_Y = 18f
    const val EAR_CENTER_X = 57f
    const val EAR_CENTER_Y = -72f
    const val EAR_RADIUS = 20f
    const val PATCH_X = 32f
    const val PATCH_RX = 22f
    const val PATCH_RY = 25f
    const val PATCH_TILT_DEG = 19f
    const val EYE_BASE_Y = -24f
    const val EYE_DROOP_RISE = 5f
    const val CHEEK_X = 51f
    const val CHEEK_Y = 3f
    const val CHEEK_RADIUS = 13f
    const val NOSE_CENTER_Y = -2f
    const val MOUTH_Y = 9f
    const val PAW_COVER_X = 30f
    const val PAW_ANNOYED_X = 10f
    const val PAW_ANNOYED_Y = 48f
    const val PAW_FEED_X = 17f
    const val PAW_FEED_Y = 24f
    const val PAW_SCRATCH_X = 62f
    const val PAW_SCRATCH_Y = -42f
    const val PAW_RX = 15f
    const val PAW_RY = 17f
    const val OPACITY_EPSILON = .005f
    const val MAX_MOTION_DT = .05f
    const val MAX_SPRING_DT = 1f / 120f
    const val POKE_SQUASH = .15f
    const val YAWN_STRETCH = .055f
    const val DELIGHT_BOUNCE = 16f
    const val BREATHE_FREQ = 1.65f
    const val BREATHE_AMP = .008f
    const val POKE_SPRING_STIFFNESS = 310f
    const val POKE_SPRING_DAMPING = 20f
    const val DELIGHT_SPRING_STIFFNESS = 230f
    const val DELIGHT_SPRING_DAMPING = 18f
    const val ROTATION_SPRING_STIFFNESS = 85f
    const val ROTATION_SPRING_DAMPING = 14f
    const val ENERGY_SPRING_STIFFNESS = 100f
    const val ENERGY_SPRING_DAMPING = 18f
}
