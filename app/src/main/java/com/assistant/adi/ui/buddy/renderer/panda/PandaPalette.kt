package com.assistant.adi.ui.buddy.renderer.panda

import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * Bao's single colour source. Every flat tone and gradient used by [PandaRig]
 * is declared here so the panda palette has exactly one owner.
 */
object PandaPalette {
    val WHITE = 0xFFFFFCF5.toInt()
    val SNOW = 0xFFE5E1D8.toInt()
    val CHARCOAL = 0xFF292D30.toInt()
    val CHARCOAL_LIGHT = 0xFF3D4243.toInt()
    val ROSE_LIGHT = 0xFFFEE2E2.toInt()
    val ROSE = 0xFFEAD1C8.toInt()
    val RED = 0xFFE38775.toInt()
    val SLATE_LIGHT = 0xFFE2E8F0.toInt()
    val SLATE = 0xFFCBD5E1.toInt()
    val SLATE_DARK = 0xFF94A3B8.toInt()
    val YELLOW_LIGHT = 0xFFFFF8E5.toInt()
    val YELLOW = 0xFFEBDDBB.toInt()
    val PINK = 0xFFDB9B88.toInt()
    val EYE_DARK = 0xFF171C1E.toInt()
    val MOUTH_OPEN = 0xFF623E40.toInt()
    val MOUTH_LINE = 0xFF353638.toInt()
    val TONGUE = 0xFFECA08F.toInt()
    val BAMBOO = 0xFF7F9C59.toInt()
    val BAMBOO_DARK = 0xFF49663D.toInt()
    val RIM = 0x665F625B
    val CHIME = 0xFFAF7C35.toInt()
    val PAD = 0xFF666966.toInt()
    val SWEAT_BLUE = 0xFF70ADCF.toInt()

    /** Soft body shading for a mood's top tone. */
    fun bodyLight(top: Int): RadialGradient =
        RadialGradient(-25f, -35f, 110f, intArrayOf(top, top and 0x00FFFFFF), null, Shader.TileMode.CLAMP)

    fun specular(): RadialGradient =
        RadialGradient(-35f, -45f, 60f, intArrayOf(0x28FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)

    fun cheek(color: Int, radius: Float): RadialGradient =
        RadialGradient(0f, 0f, radius, intArrayOf(color, color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)

    fun bellyGlow(): RadialGradient =
        RadialGradient(0f, 42f, 62f, intArrayOf(0xB3FEF08A.toInt(), 0x00FEF08A), null, Shader.TileMode.CLAMP)

    fun earGradient(radius: Float): RadialGradient =
        RadialGradient(-4f, -4f, radius, intArrayOf(CHARCOAL_LIGHT, CHARCOAL), null, Shader.TileMode.CLAMP)

    fun patchGradient(radius: Float): RadialGradient =
        RadialGradient(-3f, -3f, radius, intArrayOf(CHARCOAL_LIGHT, CHARCOAL), null, Shader.TileMode.CLAMP)

    fun shoulderGradient(radius: Float): RadialGradient =
        RadialGradient(-2f, -4f, radius, intArrayOf(CHARCOAL_LIGHT, CHARCOAL), null, Shader.TileMode.CLAMP)
}
