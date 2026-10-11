package com.example.nurbstrunner

/**
 * Hall of fame + genealogy for the evolving species. Pure Kotlin —
 * persistence lives in SaveStore.
 */
class Lineage {

    /** One link in the family tree: the form that existed before a checkpoint. */
    class Ancestor(
        val genes: FloatArray, val gen: Int,
        val fit: Float, val bias: Float, val skin: String
    )

    /** How a member of the bloodline died. */
    class DeathRecord(val cause: String, val dist: Int, val gen: Int, val skin: String)

    /** A finished run — the species' collective record. */
    class RunRecord(val dist: Int, val score: Int, val gen: Int)

    var best: FloatArray? = null
    var bestFit = -1e9f
    var bestDist = 0f
    var evolutions = 0
    var deaths = 0
    val history = ArrayList<Float>()
    val ancestors = ArrayList<Ancestor>()
    val deathLog = ArrayList<DeathRecord>()
    val runLog = ArrayList<RunRecord>()

    /** The heritable trait chromosome of the current bloodline. */
    var traits: FloatArray = Traits.defaults()

    private companion object {
        const val MAX_HIST = 8
        const val MAX_ANCESTORS = 10
        const val MAX_DEATHS = 10
    }

    fun record(g: FloatArray, f: Float) {
        if (f > bestFit) { bestFit = f; best = g.copyOf() }
        history.add(f)
        if (history.size > MAX_HIST) history.removeAt(0)
    }

    fun recordAncestor(g: FloatArray, gen: Int, fit: Float, bias: Float) {
        if (fit <= -1e8f) return
        ancestors.add(Ancestor(g.copyOf(), gen, fit, bias, describe(g)))
        if (ancestors.size > MAX_ANCESTORS) ancestors.removeAt(0)
    }

    fun recordDeath(cause: String, dist: Int, gen: Int, skin: String) {
        deathLog.add(DeathRecord(cause, dist, gen, skin))
        if (deathLog.size > MAX_DEATHS) deathLog.removeAt(0)
    }

    fun recordRun(dist: Int, score: Int, gen: Int) {
        runLog.add(RunRecord(dist, score, gen))
        if (runLog.size > GameConfig.RUN_HISTORY_MAX) runLog.removeAt(0)
    }

    /** Respawn seed: a mutated hall-of-fame champion, or a fresh random blob. */
    fun seed(): FloatArray =
        best?.let { Genetics.mutate(it.copyOf(), 1.5f) } ?: Genetics.randomGenome()

    /** Bloodline age perk: +1 start shield per 6 lifetime evolutions, max +2. */
    fun agePerk(): Int = minOf(2, evolutions / 6)

    fun describe(g: FloatArray): String {
        val pat = when (g[Genetics.PATTERN].toInt().coerceIn(0, 3)) {
            0 -> "PLAIN"; 1 -> "STRIPES"; 2 -> "RINGS"; else -> "SPOTS"
        }
        return "$pat·${hueName(g[Genetics.HUE_A])}→${hueName(g[Genetics.HUE_B])}"
    }

    fun stats(): String =
        "AGE %d · EVO %d · DEATHS %d · BEST %dm · TOP FIT %.2f"
            .format(evolutions, evolutions, deaths, bestDist.toInt(), bestFit)

    fun hallOfFame(): String {
        val sb = StringBuilder()
        if (ancestors.isEmpty()) {
            sb.append("no ancestors yet —\npass a checkpoint to evolve\n")
        } else {
            sb.append("HALL OF LINEAGE (newest first)\n")
            for (i in ancestors.indices.reversed()) {
                val a = ancestors[i]
                sb.append("#%-3d FIT %-5.2f %s  %s\n"
                    .format(a.gen, a.fit, biasName(a.bias), a.skin))
            }
        }
        if (deathLog.isNotEmpty()) {
            sb.append("\nDEATH LOG\n")
            for (i in deathLog.indices.reversed()) {
                val d = deathLog[i]
                sb.append("%s @ %dm · GEN %d · %s\n".format(d.cause, d.dist, d.gen, d.skin))
            }
        }
        return sb.toString()
    }

    fun recentRuns(): String {
        if (runLog.isEmpty()) return "no runs recorded yet"
        val sb = StringBuilder("RECENT RUNS (newest first)\n")
        for (i in runLog.indices.reversed()) {
            val r = runLog[i]
            sb.append("%5dm  %6d pts  G%d\n".format(r.dist, r.score, r.gen))
        }
        return sb.toString().trim()
    }

    private fun biasName(b: Float) =
        when { b < -0.25f -> "SLIM"; b > 0.25f -> "BULK"; else -> "BAL" }

    private fun hueName(h: Float): String {
        val x = ((h % 1f) + 1f) % 1f
        return when {
            x < 0.04f || x >= 0.96f -> "RED"
            x < 0.09f -> "ORANGE"
            x < 0.17f -> "GOLD"
            x < 0.42f -> "GREEN"
            x < 0.55f -> "CYAN"
            x < 0.72f -> "BLUE"
            x < 0.83f -> "VIOLET"
            else -> "MAGENTA"
        }
    }

    fun reset() {
        best = null; bestFit = -1e9f; bestDist = 0f
        evolutions = 0; deaths = 0
        history.clear(); ancestors.clear(); deathLog.clear(); runLog.clear()
        traits = Traits.defaults()
    }
}
