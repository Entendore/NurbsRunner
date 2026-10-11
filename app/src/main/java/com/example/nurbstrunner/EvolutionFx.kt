package com.example.nurbstrunner

import kotlin.math.sin

/**
 * Cinematic evolution sequence: CHARGE -> BURST -> GROW -> SETTLE.
 * Advances in REAL time (never scaled by its own slow-mo).
 */
class EvolutionFx {

    enum class Phase { IDLE, CHARGE, BURST, GROW, SETTLE }

    companion object {
        const val D_CHARGE = 0.75f
        const val D_BURST = 0.30f
        const val D_GROW = 0.85f
        const val D_SETTLE = 0.70f
        private const val TOTAL = D_CHARGE + D_BURST + D_GROW + D_SETTLE
    }

    var phase = Phase.IDLE
        private set
    var pt = 0f                       // 0..1 progress inside current phase
        private set

    private var burstFlag = false
    private var settleFlag = false

    val active: Boolean get() = phase != Phase.IDLE

    fun start() {
        phase = Phase.CHARGE
        pt = 0f
        burstFlag = false
        settleFlag = false
    }

    fun reset() { phase = Phase.IDLE; pt = 0f }

    fun update(dt: Float) {
        if (!active) return
        val dur = when (phase) {
            Phase.CHARGE -> D_CHARGE
            Phase.BURST -> D_BURST
            Phase.GROW -> D_GROW
            Phase.SETTLE -> D_SETTLE
            else -> return
        }
        pt += dt / dur
        if (pt >= 1f) {
            pt = 0f
            phase = when (phase) {
                Phase.CHARGE -> { burstFlag = true; Phase.BURST }
                Phase.BURST -> Phase.GROW
                Phase.GROW -> Phase.SETTLE
                else -> { settleFlag = true; Phase.IDLE }
            }
        }
    }

    fun consumeBurst(): Boolean = if (burstFlag) { burstFlag = false; true } else false
    fun consumeSettle(): Boolean = if (settleFlag) { settleFlag = false; true } else false

    // ---------------- time dilation ----------------

    fun timeScale(): Float = when (phase) {
        Phase.BURST -> 0.30f
        Phase.GROW -> 0.55f
        Phase.SETTLE -> 0.85f
        else -> 1f
    }

    // ---------------- gene expression windows ----------------

    private val nowS: Float
        get() {
            val before = when (phase) {
                Phase.CHARGE -> 0f
                Phase.BURST -> D_CHARGE
                Phase.GROW -> D_CHARGE + D_BURST
                Phase.SETTLE -> D_CHARGE + D_BURST + D_GROW
                else -> TOTAL
            }
            val dur = when (phase) {
                Phase.CHARGE -> D_CHARGE
                Phase.BURST -> D_BURST
                Phase.GROW -> D_GROW
                Phase.SETTLE -> D_SETTLE
                else -> 0f
            }
            return before + pt * dur
        }

    private fun window(t0: Float, t1: Float): Float {
        val x = ((nowS - t0) / (t1 - t0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** Skin colors morph while the creature charges up (uniform-only -> no rebuilds). */
    fun colorBlend(): Float = window(0.08f, D_CHARGE)

    /** Outline / proportions morph as the new body grows. */
    fun shapeBlend(): Float =
        window(D_CHARGE + D_BURST * 0.6f, D_CHARGE + D_BURST + D_GROW * 0.75f)

    /** Rational weights refine during settle — the final "snap into focus". */
    fun weightBlend(): Float =
        window(D_CHARGE + D_BURST + D_GROW * 0.35f, D_CHARGE + D_BURST + D_GROW + D_SETTLE * 0.55f)

    // ---------------- body presentation ----------------

    private fun backOut(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    /** Render scale of the body through the whole sequence. */
    fun bodyScale(): Float = when (phase) {
        Phase.CHARGE -> {
            val e = pt * pt
            1f - 0.42f * e + 0.025f * sin(pt * 44f) * pt
        }
        Phase.BURST -> 0.58f * (1f - pt) * (1f - pt)
        Phase.GROW -> backOut(pt)
        Phase.SETTLE -> 1f + 0.055f * sin(pt * 9.5f) * (1f - pt)
        else -> 1f
    }

    /** Extra spin (degrees) around the body axis; ends on a full turn = seamless. */
    fun spinExtra(): Float = when (phase) {
        Phase.CHARGE -> 70f * pt
        Phase.BURST -> 70f + 160f * pt
        Phase.GROW -> 230f + 130f * backOut(pt)
        Phase.SETTLE -> 360f
        else -> 0f
    }

    fun glowBoost(): Float = when (phase) {
        Phase.CHARGE -> 1.5f * pt
        Phase.BURST -> 2.6f
        Phase.GROW -> 1.1f * (1f - pt)
        Phase.SETTLE -> 0.35f * (1f - pt)
        else -> 0f
    }

    fun rimBoost(): Float = when (phase) {
        Phase.CHARGE -> 0.8f * pt
        Phase.BURST -> 1.4f
        Phase.GROW -> 0.6f * (1f - pt)
        else -> 0f
    }

    /** Camera FOV pinch (degrees) for the cinematic moment. */
    fun fovPinch(): Float = when (phase) {
        Phase.BURST -> 9f
        Phase.GROW -> 7f * (1f - pt)
        else -> 0f
    }

    /** Ground shockwave ring progress 0..1, or -1 when inactive. */
    fun ringK(): Float = when (phase) {
        Phase.BURST -> pt * 0.45f
        Phase.GROW -> (0.45f + pt * 0.55f).coerceAtMost(1f)
        else -> -1f
    }

    /** Light-pillar intensity 0..1, or -1 when inactive. */
    fun pillarK(): Float = when (phase) {
        Phase.BURST -> 1f
        Phase.GROW -> (1f - pt * 2.2f).coerceIn(0f, 1f)
        else -> -1f
    }
}
