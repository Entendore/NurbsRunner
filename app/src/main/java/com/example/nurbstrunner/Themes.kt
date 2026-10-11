package com.example.nurbstrunner

import kotlin.math.floor

object Themes {
    const val ZONE_LEN = 300f

    val NAMES = arrayOf("NEON CITY", "MAGMA CORE", "GLACIER RUN", "TOXIC REACTOR", "VOID SECTOR")

    // slots: 0 roadDark 1 roadLight 2 accentA 3 accentB 4 edge 5 fog
    val PAL: Array<Array<FloatArray>> = arrayOf(
        arrayOf(
            floatArrayOf(0.045f, 0.055f, 0.105f), floatArrayOf(0.085f, 0.095f, 0.175f),
            floatArrayOf(0.10f, 0.85f, 1.00f), floatArrayOf(1.00f, 0.20f, 0.75f),
            floatArrayOf(0.15f, 0.65f, 1.00f), floatArrayOf(0.030f, 0.045f, 0.080f)),
        arrayOf(
            floatArrayOf(0.090f, 0.040f, 0.035f), floatArrayOf(0.160f, 0.070f, 0.050f),
            floatArrayOf(1.00f, 0.45f, 0.10f), floatArrayOf(1.00f, 0.75f, 0.15f),
            floatArrayOf(1.00f, 0.35f, 0.12f), floatArrayOf(0.070f, 0.030f, 0.025f)),
        arrayOf(
            floatArrayOf(0.060f, 0.085f, 0.115f), floatArrayOf(0.105f, 0.145f, 0.190f),
            floatArrayOf(0.65f, 0.95f, 1.00f), floatArrayOf(0.45f, 0.75f, 1.00f),
            floatArrayOf(0.70f, 0.92f, 1.00f), floatArrayOf(0.050f, 0.070f, 0.100f)),
        arrayOf(
            floatArrayOf(0.035f, 0.075f, 0.050f), floatArrayOf(0.065f, 0.130f, 0.085f),
            floatArrayOf(0.45f, 1.00f, 0.25f), floatArrayOf(0.85f, 1.00f, 0.30f),
            floatArrayOf(0.40f, 1.00f, 0.35f), floatArrayOf(0.025f, 0.055f, 0.040f)),
        arrayOf(
            floatArrayOf(0.040f, 0.030f, 0.070f), floatArrayOf(0.075f, 0.055f, 0.130f),
            floatArrayOf(0.70f, 0.35f, 1.00f), floatArrayOf(1.00f, 0.35f, 0.85f),
            floatArrayOf(0.60f, 0.40f, 1.00f), floatArrayOf(0.035f, 0.025f, 0.060f))
    )

    private fun mod(i: Int, m: Int) = ((i % m) + m) % m

    fun zoneIndex(d: Float): Int = mod(floor(d / ZONE_LEN).toInt(), PAL.size)

    fun blendT(d: Float): Float {
        val fr = d / ZONE_LEN - floor(d / ZONE_LEN)
        val x = ((fr - 0.85f) / 0.15f).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** Writes the blended slot color into [out] (caller-owned, no allocation). */
    fun sampleInto(d: Float, slot: Int, out: FloatArray): FloatArray {
        val i = floor(d / ZONE_LEN).toInt()
        val t = blendT(d)
        val a = PAL[mod(i, PAL.size)][slot]
        val b = PAL[mod(i + 1, PAL.size)][slot]
        out[0] = a[0] + (b[0] - a[0]) * t
        out[1] = a[1] + (b[1] - a[1]) * t
        out[2] = a[2] + (b[2] - a[2]) * t
        return out
    }

    /** Convenience for low-frequency callers (HUD). Allocates. */
    fun sample(d: Float, slot: Int): FloatArray = sampleInto(d, slot, FloatArray(3))
}
