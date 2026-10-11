package com.example.nurbstrunner

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Nurbs {

    const val DEGREE = 3
    const val LENGTH = 1.9f
    const val BASE_RADIUS = 0.55f

    fun periodicKnots(m: Int): FloatArray {
        val k = FloatArray(m + 2 * DEGREE + 1)
        for (j in k.indices) k[j] = (j - DEGREE).toFloat() / m
        return k
    }

    fun clampedKnots(n: Int): FloatArray {
        val count = n + DEGREE + 1
        val k = FloatArray(count)
        for (j in 0 until count) {
            k[j] = when {
                j <= DEGREE -> 0f
                j >= n -> 1f
                else -> (j - DEGREE).toFloat() / (n - DEGREE)
            }
        }
        return k
    }

    fun basis(knots: FloatArray, i: Int, p: Int, t: Float): Float {
        if (p == 0) {
            return if (t >= knots[i] && t < knots[i + 1]) 1f else 0f
        }
        var sum = 0f
        val d1 = knots[i + p] - knots[i]
        if (d1 > 1e-7f) sum += (t - knots[i]) / d1 * basis(knots, i, p - 1, t)
        val d2 = knots[i + p + 1] - knots[i + 1]
        if (d2 > 1e-7f) sum += (knots[i + p + 1] - t) / d2 * basis(knots, i + 1, p - 1, t)
        return sum
    }

    /** Blunt capsule profile with an evolved taper exponent. */
    private fun profile(v: Float, taper: Float): Float {
        val s = sin(PI.toFloat() * v.pow(0.5f + taper))
        return sqrt(if (s < 0f) 0f else s) * 0.92f + 0.08f
    }

    /** Vertex layout: pos(3) + normal(3) + uv(2) = 8 floats. */
    fun buildBodyMesh(genes: FloatArray, uSamples: Int = 36, vSamples: Int = 26): MeshData {
        val m = Genetics.N_U
        val nV = Genetics.N_V
        val uKnots = periodicKnots(m)
        val vKnots = clampedKnots(nV)
        val ext = m + DEGREE

        val stretch = genes[Genetics.STRETCH]
        val twist = genes[Genetics.TWIST]
        val lump = genes[Genetics.LUMP]
        val lumpFreq = genes[Genetics.LUMP_FREQ].toInt().coerceAtLeast(2)
        val fin = genes[Genetics.FIN]
        val headG = genes[Genetics.HEAD]
        val taper = genes[Genetics.TAPER]

        val px = Array(nV) { FloatArray(ext) }
        val py = Array(nV) { FloatArray(ext) }
        val pz = FloatArray(nV)
        val w = Array(nV) { FloatArray(ext) }

        for (i in 0 until nV) {
            val v = i.toFloat() / (nV - 1)
            pz[i] = (v - 0.5f) * LENGTH * stretch
            val prof = profile(v, taper)
            for (j in 0 until ext) {
                val jj = j % m
                val theta = (2.0 * PI / m * jj).toFloat() + twist * v
                var r = BASE_RADIUS * prof * (1f + genes[Genetics.OFF + i * m + jj])
                r *= 1f + lump * sin(lumpFreq * theta + i * 1.7f)
                val headF = (1f - v).pow(3) * headG * 1.4f
                val fx = (v - 0.55f) / 0.45f
                val finF = if (fx > 0f) fx * fx * fin * 1.3f else 0f
                r *= (1f + headF + finF).coerceAtLeast(0.05f)

                px[i][j] = cos(theta) * r
                py[i][j] = sin(theta) * r
                w[i][j] = genes[Genetics.WGT + i * m + jj]
            }
        }

        val cols = uSamples
        val rows = vSamples
        val gx = Array(rows) { FloatArray(cols) }
        val gy = Array(rows) { FloatArray(cols) }
        val gz = Array(rows) { FloatArray(cols) }

        for (iv in 0 until rows) {
            val v = if (iv == rows - 1) 0.99999f else iv.toFloat() / (rows - 1)
            for (iu in 0 until cols) {
                val u = iu.toFloat() / cols
                var sx = 0f; var sy = 0f; var sz = 0f; var sw = 0f
                for (i in 0 until nV) {
                    val nv = basis(vKnots, i, DEGREE, v)
                    if (nv < 1e-6f) continue
                    for (j in 0 until ext) {
                        val mu = basis(uKnots, j, DEGREE, u)
                        if (mu < 1e-6f) continue
                        val b = nv * mu * w[i][j]
                        sx += b * px[i][j]; sy += b * py[i][j]; sz += b * pz[i]
                        sw += b
                    }
                }
                val inv = 1f / sw
                gx[iv][iu] = sx * inv
                gy[iv][iu] = sy * inv
                gz[iv][iu] = sz * inv
            }
        }

        val verts = FloatArray(cols * rows * 8)
        var ptr = 0
        for (iv in 0 until rows) {
            val uvV = iv.toFloat() / (rows - 1)
            for (iu in 0 until cols) {
                val iu1 = (iu + 1) % cols
                val iu0 = (iu - 1 + cols) % cols
                val iv1 = (iv + 1).coerceAtMost(rows - 1)
                val iv0 = (iv - 1).coerceAtLeast(0)
                val tuX = gx[iv][iu1] - gx[iv][iu0]
                val tuY = gy[iv][iu1] - gy[iv][iu0]
                val tuZ = gz[iv][iu1] - gz[iv][iu0]
                val tvX = gx[iv1][iu] - gx[iv0][iu]
                val tvY = gy[iv1][iu] - gy[iv0][iu]
                val tvZ = gz[iv1][iu] - gz[iv0][iu]
                var nx = tuY * tvZ - tuZ * tvY
                var ny = tuZ * tvX - tuX * tvZ
                var nz = tuX * tvY - tuY * tvX
                if (nx * gx[iv][iu] + ny * gy[iv][iu] < 0f) { nx = -nx; ny = -ny; nz = -nz }
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                verts[ptr++] = gx[iv][iu]; verts[ptr++] = gy[iv][iu]; verts[ptr++] = gz[iv][iu]
                verts[ptr++] = nx / len; verts[ptr++] = ny / len; verts[ptr++] = nz / len
                verts[ptr++] = iu.toFloat() / cols
                verts[ptr++] = uvV
            }
        }

        val idx = ShortArray(cols * (rows - 1) * 6)
        var ip = 0
        for (iv in 0 until rows - 1) {
            for (iu in 0 until cols) {
                val iu1 = (iu + 1) % cols
                idx[ip++] = (iv * cols + iu).toShort()
                idx[ip++] = ((iv + 1) * cols + iu).toShort()
                idx[ip++] = (iv * cols + iu1).toShort()
                idx[ip++] = (iv * cols + iu1).toShort()
                idx[ip++] = ((iv + 1) * cols + iu).toShort()
                idx[ip++] = ((iv + 1) * cols + iu1).toShort()
            }
        }
        return MeshData(verts, idx)
    }
}
