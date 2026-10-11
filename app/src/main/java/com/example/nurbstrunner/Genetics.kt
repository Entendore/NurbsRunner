package com.example.nurbstrunner

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Genome layout (120 genes):
 *
 *  SHAPE   [0..47]    radial offsets per control point      [-0.45, 0.45]
 *          [48..95]   rational NURBS weights                [ 0.55, 1.80]
 *          [96..105]  stretch, twist, lump, lumpFreq, fin,
 *                     head, taper, undulAmp, waveFreq, waveSpeed
 *  COLOR   [106..119] hueA/B, satA/B, valA/B, emissive, pulse,
 *                     sparkle, pattern, patFreq, patCon, rim, trailHue
 */
object Genetics {

    const val N_U = 8
    const val N_V = 6
    const val N_GRID = N_U * N_V

    // ---- shape ----
    const val OFF = 0
    const val WGT = N_GRID
    const val STRETCH = 2 * N_GRID      // 96
    const val TWIST = 97
    const val LUMP = 98
    const val LUMP_FREQ = 99
    const val FIN = 100
    const val HEAD = 101
    const val TAPER = 102
    const val AMP = 103
    const val WFREQ = 104
    const val WSPEED = 105

    // ---- color / skin / glow ----
    const val HUE_A = 106
    const val HUE_B = 107
    const val SAT_A = 108
    const val SAT_B = 109
    const val VAL_A = 110
    const val VAL_B = 111
    const val EMIS = 112
    const val PULSE = 113
    const val SPARK = 114
    const val PATTERN = 115             // 0 none, 1 stripes, 2 rings, 3 spots
    const val PATFREQ = 116
    const val PATCON = 117
    const val RIM = 118
    const val TRAIL = 119

    const val N_GENES = 120

    private val LO = FloatArray(N_GENES)
    private val HI = FloatArray(N_GENES)
    private val SIGMA = FloatArray(N_GENES)
    private val RANGE = FloatArray(N_GENES)

    init {
        for (k in 0 until N_GRID) {
            LO[OFF + k] = -0.45f; HI[OFF + k] = 0.45f; SIGMA[OFF + k] = 0.12f
            LO[WGT + k] = 0.55f;  HI[WGT + k] = 1.80f; SIGMA[WGT + k] = 0.15f
        }
        fun g(i: Int, lo: Float, hi: Float, s: Float) {
            LO[i] = lo; HI[i] = hi; SIGMA[i] = s
        }
        g(STRETCH,    0.85f, 1.25f, 0.05f)
        g(TWIST,     -0.90f, 0.90f, 0.10f)
        g(LUMP,       0.00f, 0.35f, 0.08f)
        g(LUMP_FREQ,  2.00f, 7.00f, 0.70f)
        g(FIN,       -0.20f, 0.90f, 0.10f)
        g(HEAD,      -0.20f, 0.70f, 0.09f)
        g(TAPER,     -0.30f, 0.30f, 0.06f)
        g(AMP,        0.00f, 0.14f, 0.03f)
        g(WFREQ,      1.00f, 4.00f, 0.40f)
        g(WSPEED,     2.00f, 9.00f, 0.80f)
        g(HUE_A,      0.00f, 1.00f, 0.05f)
        g(HUE_B,      0.00f, 1.00f, 0.05f)
        g(SAT_A,      0.35f, 1.00f, 0.06f)
        g(SAT_B,      0.35f, 1.00f, 0.06f)
        g(VAL_A,      0.50f, 1.00f, 0.05f)
        g(VAL_B,      0.50f, 1.00f, 0.05f)
        g(EMIS,       0.00f, 0.70f, 0.07f)
        g(PULSE,      2.00f, 8.00f, 0.60f)
        g(SPARK,      0.00f, 0.80f, 0.08f)
        g(PATTERN,    0.00f, 3.00f, 0.45f)
        g(PATFREQ,    2.00f, 12.0f, 0.70f)
        g(PATCON,     0.00f, 0.85f, 0.09f)
        g(RIM,        0.30f, 1.40f, 0.10f)
        g(TRAIL,      0.00f, 1.00f, 0.05f)
        for (k in 0 until N_GENES) RANGE[k] = HI[k] - LO[k]
    }

    fun clampGene(k: Int, v: Float): Float = v.coerceIn(LO[k], HI[k])
    fun clampAll(g: FloatArray) { for (k in g.indices) g[k] = clampGene(k, g[k]) }

    fun randomGenome(): FloatArray {
        val g = FloatArray(N_GENES)
        for (k in g.indices) g[k] = LO[k] + Random.nextFloat() * RANGE[k]
        return g
    }

    /** HSV -> RGB, all inputs expected in [0,1]. */
    fun hsv(h: Float, s: Float, v: Float): FloatArray {
        var hh = (h % 1f + 1f) % 1f
        val i = (hh * 6f).toInt()
        val f = hh * 6f - i
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        return when (i % 6) {
            0 -> floatArrayOf(v, t, p)
            1 -> floatArrayOf(q, v, p)
            2 -> floatArrayOf(p, v, t)
            3 -> floatArrayOf(p, q, v)
            4 -> floatArrayOf(t, p, v)
            else -> floatArrayOf(v, p, q)
        }
    }

    fun hueDist(a: Float, b: Float): Float {
        val d = abs(a - b) % 1f
        return min(d, 1f - d)
    }

    /** Decoded visual parameters handed to the renderer each frame. */
    class Appearance(
        val colA: FloatArray, val colB: FloatArray, val trail: FloatArray,
        val pattern: Int, val patFreq: Float, val patCon: Float,
        val emissive: Float, val pulse: Float, val sparkle: Float,
        val rim: Float, val amp: Float, val wfreq: Float, val wspeed: Float
    )

    fun appearance(g: FloatArray): Appearance = Appearance(
        colA = hsv(g[HUE_A], g[SAT_A], g[VAL_A]),
        colB = hsv(g[HUE_B], g[SAT_B], g[VAL_B]),
        trail = hsv(g[TRAIL], 0.85f, 1f),
        pattern = g[PATTERN].toInt().coerceIn(0, 3),
        // integer frequency keeps procedural patterns seamless around the ring
        patFreq = round(g[PATFREQ]).coerceAtLeast(2f),
        patCon = g[PATCON],
        emissive = g[EMIS], pulse = g[PULSE], sparkle = g[SPARK], rim = g[RIM],
        amp = g[AMP], wfreq = g[WFREQ], wspeed = g[WSPEED]
    )

    /** Mean core radius factor — drives hitbox and pickup radius. */
    fun sizeFactor(g: FloatArray): Float {
        var s = 0f
        for (k in 0 until N_GRID) s += 1f + g[OFF + k]
        val r0 = s / N_GRID
        val extra = 1f + 0.35f * g[HEAD] + 0.20f * g[FIN]
        return (r0 * extra).coerceIn(0.6f, 1.6f)
    }

    // ------------------------------------------------------------------
    //  FITNESS
    // ------------------------------------------------------------------

    fun fitness(g: FloatArray, envBias: Float): Float {
        // 1) surface smoothness
        var rough = 0f; var n = 0
        for (i in 0 until N_V) {
            for (j in 0 until N_U) {
                val r = g[OFF + i * N_U + j]
                rough += abs(g[OFF + i * N_U + (j + 1) % N_U] - r); n++
                if (i < N_V - 1) { rough += abs(g[OFF + (i + 1) * N_U + j] - r); n++ }
            }
        }
        val smooth = (1f - (rough / n) / 0.30f).coerceIn(0f, 1f)

        // 2) bilateral symmetry
        var asym = 0f
        for (i in 0 until N_V) {
            for (j in 1 until N_U / 2) {
                asym += abs(g[OFF + i * N_U + j] - g[OFF + i * N_U + (N_U - j) % N_U])
            }
        }
        val sym = (1f - (asym / (N_V * (N_U / 2 - 1)).toFloat()) / 0.30f).coerceIn(0f, 1f)

        // 3) adaptive frontal-area target
        val t = (envBias + 1f) * 0.5f
        val target = 0.78f + t * (1.18f - 0.78f)
        var head = 0f; var cnt = 0
        for (i in 0..1) for (j in 0 until N_U) { head += 1f + g[OFF + i * N_U + j]; cnt++ }
        head /= cnt
        val frontal = (1f - abs(head - target) / 0.35f).coerceIn(0f, 1f)

        // 4) weight sanity
        var wdev = 0f
        for (k in 0 until N_GRID) wdev += abs(g[WGT + k] - 1f)
        val wsane = (1f - (wdev / N_GRID) / 0.6f).coerceIn(0f, 1f)

        // 5) COLOR: harmony (analogous OR complementary — muddy middle punished)
        val dh = hueDist(g[HUE_A], g[HUE_B])
        val harmony = when {
            dh < 0.18f -> 1f
            dh in 0.40f..0.60f -> (1f - abs(dh - 0.5f) * 2.5f).coerceIn(0f, 1f)
            else -> 0.35f
        }
        val sat = (g[SAT_A] + g[SAT_B]) / 2f
        val vlm = (g[VAL_A] + g[VAL_B]) / 2f
        val vivid = (sat * (1f - abs(vlm - 0.85f) * 1.4f)).coerceIn(0f, 1f)
        val ax = cos(2.0 * PI * g[HUE_A]) + cos(2.0 * PI * g[HUE_B])
        val ay = sin(2.0 * PI * g[HUE_A]) + sin(2.0 * PI * g[HUE_B])
        val avgHue = ((atan2(ay, ax) / (2.0 * PI)) + 1.0) % 1.0
        // environmental hue: slim -> cool (cyan/blue ~0.55), bulk -> warm (red/orange ~0.06)
        val targetHue = 0.55f + (0.06f - 0.55f) * t
        val hueEnv = (1f - hueDist(avgHue.toFloat(), targetHue) / 0.35f).coerceIn(0f, 1f)
        val mud = if (sat < 0.45f && vlm in 0.40f..0.75f) 0.55f else 1f
        val colorF = (0.55f * harmony + 0.45f * vivid) * mud * 0.7f + 0.85f * hueEnv

        // 6) PATTERN coherence
        val pf = g[PATFREQ]; val pc = g[PATCON]; val ptype = g[PATTERN]
        val intFit = (1f - abs(pf - round(pf)) / 0.5f).coerceIn(0f, 1f)
        val cTarget = 0.25f + 0.50f * t
        val cFit = (1f - abs(pc - cTarget) / 0.5f).coerceIn(0f, 1f)
        val patternF = if (ptype < 0.5f) 0.50f + 0.30f * (1f - t)
                       else 0.45f + 0.30f * intFit + 0.25f * cFit

        // 7) MOTION sanity
        val amp = g[AMP]
        val ampF = if (amp in 0.02f..0.10f) 1f
                   else (1f - (if (amp < 0.02f) (0.02f - amp) else (amp - 0.10f)) * 10f).coerceIn(0f, 1f)
        val spdF = (1f - max(0f, g[WSPEED] - 7f) / 3f).coerceIn(0f, 1f)
        val motionF = 0.6f * ampF + 0.4f * spdF

        return 0.8f * smooth + 0.7f * sym + 1.2f * frontal + 0.4f * wsane +
               colorF + 0.8f * patternF + 0.6f * motionF
    }

    // ------------------------------------------------------------------
    //  VARIATION OPERATORS
    // ------------------------------------------------------------------

    fun crossover(a: FloatArray, b: FloatArray): FloatArray {
        val child = FloatArray(N_GENES)
        if (Random.nextFloat() < 0.5f) {
            // BLX-alpha blend
            val alpha = 0.3f
            for (k in 0 until N_GENES) {
                val lo = min(a[k], b[k]); val hi = max(a[k], b[k]); val d = hi - lo
                child[k] = lo - alpha * d + Random.nextFloat() * (d * (1f + 2f * alpha))
            }
        } else {
            // uniform crossover
            for (k in 0 until N_GENES) child[k] = if (Random.nextFloat() < 0.5f) a[k] else b[k]
        }
        clampAll(child)
        return child
    }

    fun mutate(g: FloatArray, rate: Float = 1f): FloatArray {
        for (k in g.indices) {
            if (Random.nextFloat() < 0.16f * rate) {
                g[k] += Random.nextGaussian().toFloat() * SIGMA[k] * rate
            }
        }
        clampAll(g)
        return g
    }

    /** Normalized genome distance used for speciation. */
    fun genomeDist(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (k in a.indices) d += abs(a[k] - b[k]) / RANGE[k]
        return d / a.size
    }

    private fun tournament(pop: List<FloatArray>, fits: FloatArray): FloatArray {
        var best = Random.nextInt(pop.size)
        repeat(2) {
            val c = Random.nextInt(pop.size)
            if (fits[c] > fits[best]) best = c
        }
        return pop[best]
    }

    /**
     * Full evolution step with fitness sharing (speciation), adaptive mutation
     * and mixed crossover. Returns (bestGenome, bestFitness, speciesCount).
     */
    fun evolve(seed: FloatArray, envBias: Float,
               generations: Int = 8, popSize: Int = 16): Triple<FloatArray, Float, Int> {

        var pop = Array(popSize) { i ->
            when {
                i == 0 -> seed.copyOf()
                i == 1 -> mutate(seed.copyOf(), 2.2f)
                i == popSize - 1 -> randomGenome()
                i == popSize - 2 -> randomGenome()
                else -> mutate(seed.copyOf())
            }
        }

        var mutRate = 1f
        var prevBest = -1e9f
        var stale = 0
        var speciesCount = 0
        var bestGenome = seed.copyOf()
        var bestFit = fitness(seed, envBias)

        repeat(generations) {
            // ---- evaluate + speciate (fitness sharing) ----
            val fits = FloatArray(popSize) { fitness(pop[it], envBias) }
            val spOf = IntArray(popSize) { -1 }
            val spSize = HashMap<Int, Int>()
            speciesCount = 0
            for (i in 0 until popSize) {
                var found = -1
                for (r in 0 until i) {
                    if (spOf[r] >= 0 && genomeDist(pop[i], pop[r]) < 0.13f) { found = spOf[r]; break }
                }
                if (found < 0) { found = speciesCount++; spSize[found] = 0 }
                spOf[i] = found
                spSize[found] = spSize[found]!! + 1
            }
            val shared = FloatArray(popSize) {
                fits[it] / sqrt(spSize[spOf[it]]!!.toFloat())
            }

            val order = (0 until popSize).sortedByDescending { shared[it] }

            val genBest = pop[order[0]]
            val genBestFit = fitness(genBest, envBias)
            if (genBestFit > bestFit) { bestFit = genBestFit; bestGenome = genBest.copyOf() }

            // ---- adaptive mutation: stagnation boosts exploration ----
            if (genBestFit < prevBest + 0.01f) stale++ else stale = 0
            prevBest = max(prevBest, genBestFit)
            mutRate = (1f + stale * 0.5f).coerceAtMost(2.5f)

            // ---- build next generation ----
            val ranked = order.map { pop[it] }
            val rankedShared = FloatArray(popSize) { shared[order[it]] }
            val next = ArrayList<FloatArray>(popSize)
            next.add(ranked[0].copyOf())                    // elitism x2
            next.add(ranked[1].copyOf())
            while (next.size < popSize) {
                val a = tournament(ranked, rankedShared)
                val b = tournament(ranked, rankedShared)
                next.add(mutate(crossover(a, b), mutRate))
            }
            pop = next.toTypedArray()
        }
        return Triple(bestGenome, bestFit, speciesCount)
    }
}
