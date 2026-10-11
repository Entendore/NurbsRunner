package com.example.nurbstrunner

import kotlin.math.floor
import kotlin.random.Random

/**
 * Smooth 1-D Catmull-Rom field over track distance (meters), built from a
 * lazy random walk. Used for road bend (lateral) and hills (height).
 */
class TrackPath(
    private val rng: Random,
    private val spacing: Float,
    private val stepAmp: Float,
    private val maxAbs: Float,
    private val straightChance: Float
) {
    private val anchors = ArrayList<Float>()

    init {
        anchors.add(0f)
        var v = 0f
        repeat(16) { v = next(v); anchors.add(v) }
    }

    private fun next(prev: Float): Float {
        var v = prev + (rng.nextFloat() * 2f - 1f) * stepAmp
        if (rng.nextFloat() < straightChance) v *= 0.15f
        return v.coerceIn(-maxAbs, maxAbs)
    }

    private fun at0(i: Int): Float {
        while (anchors.size <= i + 3) anchors.add(next(anchors[anchors.size - 1]))
        return if (i < 0) anchors[0] else anchors[i]
    }

    fun at(d: Float): Float {
        val f = d / spacing
        val i = floor(f).toInt()
        val t = f - i
        val p0 = at0(i - 1); val p1 = at0(i); val p2 = at0(i + 1); val p3 = at0(i + 2)
        val t2 = t * t; val t3 = t2 * t
        return 0.5f * (2f * p1 + (-p0 + p2) * t +
                (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                (3f * p1 - p0 - 3f * p2 + p3) * t3)
    }
}
