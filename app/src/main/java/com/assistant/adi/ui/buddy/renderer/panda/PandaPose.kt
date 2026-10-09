package com.assistant.adi.ui.buddy.renderer.panda

enum class EyeStyle { OPEN, HAPPY_ARC, SIDE_GLANCE, STRAINED, SPIRAL, FLAT, WIDE }
enum class MouthStyle { SMILE, TONGUE, OPEN, FLAT, STRAINED, PET, POUT }
enum class Accessory { NONE, QUESTION, SWEAT, BOLT }

/** Continuous posture fields blend before expression choreography. */
data class PandaPose(
    val headTilt: Float = 0f,
    val headX: Float = 0f,
    val headY: Float = 0f,
    val bodyTilt: Float = 0f,
    val compression: Float = 0f,
    val patchDroop: Float = 0f,
    val eyeOpen: Float = 1f,
    val eyeStyle: EyeStyle = EyeStyle.OPEN,
    val mouth: MouthStyle = MouthStyle.SMILE,
    val bodyTop: Int = PandaPalette.WHITE,
    val bodyBot: Int = PandaPalette.SNOW,
    val cheek: Int = PandaPalette.PINK,
    val cheekAlpha: Float = .38f,
    val cheekPuff: Float = 0f,
    val gazeX: Float = 0f,
    val coverEyes: Float = 0f,
    val pawScratch: Float = 0f,
    val pawX: Float = 32f,
    val pawY: Float = 58f,
    val pawAsym: Float = 0f,
    val footSpread: Float = 0f,
    val earDrop: Float = 0f,
    val asym: Float = 0f,
    val glow: Float = 0f,
    val bamboo: Boolean = false,
    val accessory: Accessory = Accessory.NONE
)

/** BuddyMood ordinal order; Bao's ninth posture shares the same anatomy. */
object PandaPoses {
    val ALL = arrayOf(
        PandaPose(headTilt = -2f, pawX = 27f, pawY = 57f, pawAsym = -5f, bamboo = true),
        PandaPose(headTilt = 4f, headX = 3f, headY = 8f, bodyTilt = -2f,
            compression = .055f, patchDroop = .8f, eyeOpen = .55f, eyeStyle = EyeStyle.STRAINED,
            mouth = MouthStyle.TONGUE, bodyTop = PandaPalette.ROSE_LIGHT, bodyBot = PandaPalette.ROSE,
            cheek = PandaPalette.RED, cheekAlpha = .65f, pawX = 53f, pawY = 65f,
            footSpread = 5f, earDrop = 12f, accessory = Accessory.SWEAT),
        PandaPose(headTilt = 9f, headX = 6f, headY = 13f, bodyTilt = 3f,
            compression = .10f, patchDroop = 1f, eyeOpen = .3f, eyeStyle = EyeStyle.FLAT,
            mouth = MouthStyle.FLAT, bodyTop = PandaPalette.SLATE_LIGHT, bodyBot = PandaPalette.SLATE,
            cheekAlpha = .12f, pawX = 19f, pawY = 59f, pawAsym = 3f, footSpread = -6f, earDrop = 19f),
        PandaPose(headTilt = -8f, headX = -3f, bodyTilt = 2f, patchDroop = .15f,
            mouth = MouthStyle.FLAT, pawScratch = 0f, pawX = 36f, pawY = 62f,
            pawAsym = 4f, asym = .65f, accessory = Accessory.QUESTION),
        PandaPose(headTilt = -3f, headY = 7f, compression = .075f,
            eyeStyle = EyeStyle.SPIRAL, mouth = MouthStyle.STRAINED, coverEyes = 1f,
            footSpread = -3f, earDrop = 10f, accessory = Accessory.SWEAT),
        PandaPose(headTilt = -6f, headX = -5f, headY = 4f, bodyTilt = -3f,
            compression = .025f, patchDroop = .4f, eyeOpen = .65f, eyeStyle = EyeStyle.SIDE_GLANCE,
            mouth = MouthStyle.FLAT, cheekAlpha = .16f, pawX = 43f, pawY = 67f,
            pawAsym = -3f, footSpread = 3f, earDrop = 7f),
        PandaPose(headTilt = 3f, headY = -3f, bodyTilt = -2f, compression = -.02f,
            eyeStyle = EyeStyle.HAPPY_ARC, bodyTop = PandaPalette.YELLOW_LIGHT, bodyBot = PandaPalette.YELLOW,
            cheekAlpha = .45f, pawX = 22f, pawY = 50f, footSpread = 4f, glow = .65f, accessory = Accessory.BOLT),
        PandaPose(headTilt = 8f, headX = 7f, headY = -7f, bodyTilt = -3f,
            compression = -.035f, eyeOpen = 1.1f, eyeStyle = EyeStyle.WIDE,
            mouth = MouthStyle.OPEN, pawX = 40f, pawY = 60f, pawAsym = -8f, footSpread = -2f, earDrop = -5f),
        PandaPose(headTilt = -5f, headX = -4f, bodyTilt = 2f, compression = .025f,
            eyeOpen = .85f, eyeStyle = EyeStyle.SIDE_GLANCE, gazeX = -4f,
            mouth = MouthStyle.POUT, cheekAlpha = .68f, cheekPuff = 5f,
            pawX = -11f, pawY = 52f, pawAsym = 3f, footSpread = -2f)
    )
}
