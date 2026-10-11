package com.example.nurbstrunner

import kotlin.random.Random

/**
 * The trait chromosome: 6 heritable genes in [0,1] that map to real gameplay
 * modifiers. Evolved at every checkpoint from accumulated gameplay pressure,
 * persisted across runs — the species literally adapts to how it dies.
 *
 * Index order is fixed; pressure arrays use the same order.
 */
object Traits {
    const val N = 6

    // HUD short names (keep 3 chars) and long names
    val NAMES = arrayOf("HID", "HUN", "FER", "SWF", "SPR", "PUL")
    val LONG = arrayOf("HIDE", "HUNGER", "FERAL", "SWIFT", "SPRING", "PULL")
    private val BRANCH_NAME = arrayOf("ARMORED", "HUNGRY", "FERAL", "SWIFT", "SPRINGY", "PULLER")

    // pressure indices (same order)
    const val P_HIDE = 0; const val P_HUNGER = 1; const val P_FERAL = 2
    const val P_SWIFT = 3; const val P_SPRING = 4; const val P_PULL = 5

    fun defaults(): FloatArray = FloatArray(N) { 0.5f }
    fun clamp(t: FloatArray) { for (i in t.indices) t[i] = t[i].coerceIn(0f, 1f) }

    /**
     * Pull each trait toward its normalized pressure [0..1], with a slight
     * regression toward 0.5 (so traits never max out permanently) and a
     * whisper of mutation. rate > 1 = more divergent branches.
     */
    fun evolve(t: FloatArray, pressure: FloatArray, rate: Float = 1f): FloatArray {
        for (i in t.indices) {
            val p = pressure[i].coerceIn(0f, 1f)
            t[i] += (p * 0.18f - 0.015f) * rate +
                    Random.nextGaussian().toFloat() * 0.02f * rate
        }
        clamp(t)
        return t
    }

    // ---------------- gameplay modifiers ----------------

    /** HIDE: hits required to consume one shield (2 when well-armored). */
    fun hitsPerShield(t: FloatArray): Int = if (t[P_HIDE] > 0.5f) 2 else 1

    /** HUNGER: orb score multiplier. */
    fun orbMult(t: FloatArray): Float = 1f + t[P_HUNGER]

    /** FERAL: overdrive duration multiplier. */
    fun overdriveMult(t: FloatArray): Float = 1f + t[P_FERAL]

    /** SWIFT: speed-cap bonus (m/s). */
    fun speedCapBonus(t: FloatArray): Float = t[P_SWIFT] * 4f

    /** SWIFT: acceleration multiplier. */
    fun accelMult(t: FloatArray): Float = 1f + t[P_SWIFT] * 0.3f

    /** SPRING: jump velocity bonus (m/s). */
    fun jumpBonus(t: FloatArray): Float = t[P_SPRING] * 2.5f

    /** PULL: always-on orb attraction radius (0 = off). */
    fun magnetRadius(t: FloatArray): Float = t[P_PULL] * 2.2f

    // ---------------- presentation ----------------

    fun describe(t: FloatArray): String {
        val sb = StringBuilder()
        for (i in t.indices) sb.append(NAMES[i]).append("%02d".format((t[i] * 99).toInt())).append(' ')
        return sb.toString().trim()
    }

    /** Branch label from the dominant trait, or BALANCED when flat. */
    fun branchLabel(t: FloatArray): String {
        var maxI = 0; var minV = 2f; var maxV = -1f
        for (i in t.indices) {
            if (t[i] > maxV) { maxV = t[i]; maxI = i }
            if (t[i] < minV) minV = t[i]
        }
        return if (maxV - minV < 0.12f) "BALANCED" else BRANCH_NAME[maxI]
    }
}
