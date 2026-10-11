package com.example.nurbstrunner

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** One split of the road: diverges at forkD, merges at mergeD. */
class ForkSpan(
    val forkD: Float, val mergeD: Float,
    val riskySide: Int,
    val labelL: String, val labelR: String
) {
    fun contains(d: Float): Boolean = d >= forkD && d <= mergeD

    fun envAt(d: Float): Float {
        if (d < forkD || d > mergeD) return 0f
        val tIn = ((d - forkD) / GameConfig.FORK_ENV_LEN).coerceIn(0f, 1f)
        val tOut = ((mergeD - d) / GameConfig.FORK_ENV_LEN).coerceIn(0f, 1f)
        val x = min(tIn, tOut)
        return x * x * (3f - 2f * x)
    }
}

/** Difficulty cost per pattern — drives the tension director. */
internal val PATTERN_COST = floatArrayOf(
    0.40f, 0.50f, 0.35f, 0.10f, 0.70f, 0.30f, 0.30f, 0.40f,
    0.60f, 0.85f, 0.50f, 0.75f, 0.80f, 0.90f, 0.20f, 0.95f)

class GameWorld(
    private val settings: Settings,
    val lineage: Lineage,
    private val sfx: GameSfx,
    private val telemetry: Telemetry = Telemetry.NONE,
    private val ads: AdGateway = AdGateway.NONE
) {

    // kind: 0 wall · 1 low bar · 2 jump pad · 3 boost pad · 4 VOID chasm · 5 tunnel hang
    // side: 0 main road · 1 left branch · 2 right branch
    class Obstacle(
        val lane: Int, val kind: Int, var z: Float,
        var len: Float = 0f, val side: Int = 0
    ) {
        var scored = false
        var used = false
        var drift = 0f
        var phase = 0f
        fun xAt(t: Float): Float =
            (lane - (if (side == 0) (GameConfig.LANES - 1) / 2f else 1f)) * GameConfig.LANE_W +
                (if (drift != 0f) sin(t * 1.6f + phase) * drift else 0f)
        val solid: Boolean get() = kind == 0 || kind == 1 || kind == 5
    }

    // kind: 0 orb · 1 magnet · 2 overdrive · 3 shield
    class Orb(
        var x: Float, var y: Float, var z: Float,
        val kind: Int, val side: Int = 0
    ) { var taken = false }

    class Gate(var z: Float, var passed: Boolean)
    class Decor(val d: Float, val kind: Int, val x: Float, val s: Float)

    class Particle(
        var x: Float = 0f, var y: Float = 0f, var z: Float = 0f,
        var vx: Float = 0f, var vy: Float = 0f, var vz: Float = 0f,
        var life: Float = 0f, var maxLife: Float = 1f,
        var size: Float = 0.1f, var stretch: Float = 1f, var side: Int = 0
    )

    class BranchCandidate(
        val genes: FloatArray, val traits: FloatArray,
        val label: String, val skin: String
    )

    val obstacles = ArrayList<Obstacle>()
    val orbs = ArrayList<Orb>()
    val gates = ArrayList<Gate>()
    val decor = ArrayList<Decor>()
    val trail = ArrayList<Particle>()
    val fx = EvolutionFx()
    val forks = ArrayList<ForkSpan>()

    var ghostGenes: FloatArray? = null
    @Volatile var ghostDirty = false

    @Volatile var gameOver = false
    @Volatile var meshDirty = true

    /** False while paused or during the lineage-branch death screen. */
    @Volatile var inputEnabled = true
    @Volatile var reviveOffer = false

    var lane = (GameConfig.LANES - 1) / 2
    var roadSide = 0                 // 0 main · 1 left branch · 2 right branch
    var playerX = 0f
    var jumpY = 0f
    var vy = 0f
    var grounded = true

    var speed = GameConfig.START_SPEED
    var distance = 0f
    var scroll = 0f
    var score = 0f
    var shield = GameConfig.START_SHIELD
    var invuln = 0f
    var flash = 0f
    var shake = 0f
    var t = 0f

    // ---- game-feel state ----
    var hitStop = 0f                 // seconds of sim near-freeze
    var squash = 0f                  // 0..1 body deform (renderer reads this)
    var styleCombo = 0
    var styleT = 0f
    val styleMult: Int
        get() = 1 + min(GameConfig.STYLE_MAX_MULT - 1, styleCombo / 4)
    var cleanT = 0f                  // seconds since last hit
    private var hitsSinceCP = 0
    private var nextMilestone = GameConfig.MILESTONE_STEP
    private var usedWind = false
    private var hardStreak = 0       // tension director

    var magnetT = 0f
    var overT = 0f
    var combo = 0
    var comboT = 0f

    var genes = Genetics.randomGenome()
    var startGenes = genes.copyOf()
    var targetGenes = genes.copyOf()
    var generation = 0
    var lastFitness = 0f
    var lastDelta = 0f
    var lastSpecies = 0
    var sizeFactor = 1f
    var envBias = 0f
    var evoCharge = 0f

    var traits = Traits.defaults()
    private val pressure = FloatArray(Traits.N)

    var branches: List<BranchCandidate>? = null
        private set
    val branchChoiceActive: Boolean get() = gameOver && branches != null

    @Volatile var announceMsg = ""
    @Volatile var announceT = 0f
    private var announcePri = 0

    @Volatile private var saveDirty = false
    fun requestSave() { saveDirty = true }

    /**
     * Builds the save JSON ON the GL thread — the only thread that mutates
     * Lineage — so it is always a consistent snapshot. The UI receives an
     * immutable String; nothing mutable crosses threads.
     */
    fun takeSaveJson(): String? {
        if (!saveDirty) return null
        saveDirty = false
        return runCatching { SaveStore.snapshot(settings, lineage) }
            .onFailure { Logx.e("Save", "snapshot failed: ${it.message}", it) }
            .getOrNull()
    }

    private var pendingScore = 0f
    private var pendingShield = false
    private var pendingMsg = ""

    private var pressureDamage = 0f
    private var pressureOrbs = 0f
    private var distSinceRow = 0f
    private var rowGap = 30f
    private var nextCheckpoint = GameConfig.CHECKPOINT_EVERY
    private var nextDecorD = 0f
    private var trailAcc = 0f
    private var streakAcc = 0f
    private var chargeAcc = 0f
    private var jumpBuf = 0f
    private var acc = 0f
    private var lastSb = 0f
    private var lastWb = 0f
    private var lastFxPhase = EvolutionFx.Phase.IDLE
    private var hitStreak = 0
    private var watchdogT = 0f

    private var section = 0
    private var sectionEnd = 220f

    private var nextForkD = GameConfig.FORK_FIRST
    private var forkHoldUntil = 0f
    private var forkAnnounceD = -1f
    private var activeSpan: ForkSpan? = null
    private var currentRisky = false

    private lateinit var bend: TrackPath
    private lateinit var hill: TrackPath
    private lateinit var amp: TrackPath
    private var rng = Random(System.nanoTime())
    private val pool = ArrayDeque<Particle>()

    private val inputQ = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    fun queue(i: Int) { inputQ.add(i) }

    init { reset() }

    // ---------------- lifecycle ----------------

    fun reset(seedGenes: FloatArray? = null, seedTraits: FloatArray? = null) {
        pool.addAll(trail); trail.clear()
        obstacles.clear(); orbs.clear(); gates.clear(); decor.clear()
        forks.clear()
        lane = (GameConfig.LANES - 1) / 2
        roadSide = 0; activeSpan = null; currentRisky = false
        playerX = 0f; jumpY = 0f; vy = 0f; grounded = true
        speed = GameConfig.START_SPEED; distance = 0f; scroll = 0f; score = 0f
        gameOver = false; invuln = 0f; flash = 0f; shake = 0f
        hitStop = 0f; squash = 0f
        styleCombo = 0; styleT = 0f; cleanT = 0f
        hitsSinceCP = 0; nextMilestone = GameConfig.MILESTONE_STEP
        usedWind = false; hardStreak = 0
        reviveOffer = false
        magnetT = 0f; overT = 0f; combo = 0; comboT = 0f
        distSinceRow = 0f; rowGap = 30f; nextCheckpoint = GameConfig.CHECKPOINT_EVERY
        nextDecorD = 0f; trailAcc = 0f; streakAcc = 0f; chargeAcc = 0f; jumpBuf = 0f; acc = 0f
        pressureDamage = 0f; pressureOrbs = 0f; evoCharge = 0f
        announceMsg = ""; announceT = 0f; announcePri = 0
        pendingScore = 0f; pendingShield = false; pendingMsg = ""
        fx.reset(); lastSb = 0f; lastWb = 0f; lastFxPhase = EvolutionFx.Phase.IDLE
        hitStreak = 0
        pressure.fill(0f)
        branches = null
        section = 0; sectionEnd = 220f
        nextForkD = GameConfig.FORK_FIRST
        forkHoldUntil = 0f; forkAnnounceD = -1f

        rng = Random(System.nanoTime())
        bend = TrackPath(rng, GameConfig.BEND_SPACING, GameConfig.BEND_STEP,
            GameConfig.BEND_MAX, 0.20f)
        hill = TrackPath(rng, GameConfig.HILL_SPACING, GameConfig.HILL_STEP,
            GameConfig.HILL_MAX, 0.30f)
        amp = TrackPath(rng, GameConfig.AMP_SPACING, GameConfig.AMP_STEP,
            GameConfig.AMP_MAX, 0.25f)

        genes = (seedGenes ?: lineage.seed()).copyOf()
        Genetics.clampAll(genes)
        startGenes = genes.copyOf(); targetGenes = genes.copyOf()
        traits = (seedTraits ?: lineage.traits).copyOf()
        Traits.clamp(traits)

        generation = 0; lastSpecies = 0; lastDelta = 0f
        lastFitness = Genetics.fitness(genes, 0f)
        sizeFactor = Genetics.sizeFactor(genes)
        meshDirty = true

        val perk = lineage.agePerk()
        shield = GameConfig.START_SHIELD + perk
        if (perk > 0) {
            announce("BLOODLINE AGE %d · +%d START SHIELD".format(lineage.evolutions, perk), 2.6f)
        }

        ghostGenes = lineage.best?.copyOf()
        ghostDirty = true
        telemetry.event("run_start", mapOf(
            "generation" to lineage.evolutions, "traits" to Traits.describe(traits)))
    }

    fun laneX(l: Int) = (l - (GameConfig.LANES - 1) / 2f) * GameConfig.LANE_W

    fun bendAt(d: Float): Float {
        val m = 0.45f + 0.55f * abs(amp.at(d))
        return bend.at(d) * m
    }
    fun heightAt(d: Float): Float = hill.at(d)

    // ---------------- fork field helpers ----------------

    fun envAt(d: Float): Float {
        for (f in forks) { val e = f.envAt(d); if (e > 0f) return e }
        return 0f
    }

    fun sideOffAt(d: Float, side: Int): Float = when (side) {
        1 -> -GameConfig.FORK_SPREAD * envAt(d)
        2 -> GameConfig.FORK_SPREAD * envAt(d)
        else -> 0f
    }

    /** Where the lane controller wants the player (branch offset included). */
    fun targetPlayerX(): Float = if (roadSide == 0) laneX(lane)
        else sideOffAt(scroll, roadSide) + (lane - 1) * GameConfig.LANE_W

    fun ghostLaneX(): Float = when (roadSide) {
        1 -> -GameConfig.FORK_SPREAD * envAt(scroll) - 2.4f
        2 -> GameConfig.FORK_SPREAD * envAt(scroll) + 2.4f
        else -> -4.7f
    }

    fun roadLabel(): String = when (roadSide) { 1 -> "L"; 2 -> "R"; else -> "M" }

    /** "L:ORBS R:VOID RUN" when a fork is within the preview window, else null. */
    fun forkPreview(): String? {
        for (f in forks) {
            if (f.forkD - scroll in 0f..GameConfig.FORK_PREVIEW_DIST) {
                return "L:${f.labelL} R:${f.labelR}"
            }
        }
        return null
    }

    fun hangBottom() = 1.15f + 0.75f * sizeFactor

    private fun jumpV() = GameConfig.JUMP_V + Traits.jumpBonus(traits)
    private fun padJumpV() = GameConfig.PAD_JUMP_V + Traits.jumpBonus(traits)
    private fun speedCap() = 26f + min(9f, distance / 500f) + Traits.speedCapBonus(traits)

    /** Priority-announcing: lower priority never interrupts a live higher one. */
    fun announce(m: String, dur: Float = 2.2f, pri: Int = 1) {
        if (announceT > 0f && pri < announcePri) return
        announceMsg = m; announceT = dur; announcePri = pri
    }

    // ---------------- style system ----------------

    /** Any moment of skill calls this: builds the multiplier and pays instantly. */
    fun styleAward(points: Float) {
        styleCombo++
        styleT = GameConfig.STYLE_WINDOW
        score += points * styleMult
    }

    // ---------------- fixed-timestep front door ----------------

    fun update(dtRaw: Float) {
        fx.update(dtRaw)
        if (fx.phase != lastFxPhase) {
            if (fx.phase == EvolutionFx.Phase.GROW) sfx.play(Sfx.GROW)
            lastFxPhase = fx.phase
        }
        handleFxEvents()
        processInput()

        val ts = if (gameOver) 1f else fx.timeScale()
        val eff = if (hitStop > 0f) ts * 0.12f else ts   // hit-stop: sim crawls
        hitStop = (hitStop - dtRaw).coerceAtLeast(0f)

        acc += (dtRaw * eff).coerceIn(0f, 0.1f)
        var n = 0
        while (acc >= GameConfig.SIM_STEP && n < GameConfig.MAX_CATCHUP_STEPS) {
            sim(GameConfig.SIM_STEP); acc -= GameConfig.SIM_STEP; n++
        }
        if (n == GameConfig.MAX_CATCHUP_STEPS) {
            acc = 0f
            watchdogT += dtRaw
            if (watchdogT >= 1f) {
                watchdogT = 0f
                Logx.d("Sim", "catch-up saturating — frame budget exceeded")
            }
        }
    }

    private fun handleFxEvents() {
        if (fx.consumeBurst()) {
            flash = 1f
            shake = max(shake, 0.85f)
            hitStop = max(hitStop, 0.05f)
            score += pendingScore
            if (pendingShield && shield < GameConfig.MAX_SHIELD) shield++
            repeat(28) { explode() }
            announce(pendingMsg, 3.2f, 2)
            sfx.play(Sfx.BURST); sfx.buzz(Buzz.HEAVY)
        }
        if (fx.consumeSettle()) {
            for (k in genes.indices) genes[k] = targetGenes[k]
            sizeFactor = Genetics.sizeFactor(genes)
            meshDirty = true
            ringSparkle()
            sfx.play(Sfx.SETTLE)
        }
    }

    private fun laneMax() = if (roadSide == 0) GameConfig.LANES - 1 else 2

    private fun processInput() {
        while (true) {
            val i = inputQ.poll() ?: break
            when (i) {
                Input.RESTART -> reset()
                Input.REVIVE -> if (gameOver && !branches.isNullOrEmpty()) doAdRevive()
                Input.BRANCH_0, Input.BRANCH_1, Input.BRANCH_2 -> {
                    val b = branches?.getOrNull(i - Input.BRANCH_0) ?: continue
                    lineage.traits = b.traits.copyOf()
                    reset(b.genes, b.traits)
                    telemetry.event("lineage_branch", mapOf("label" to b.label))
                }
                Input.LEFT -> if (inputEnabled) when {
                    roadSide == 2 && lane == 0 && envAt(scroll) < GameConfig.CROSS_MAX_ENV -> {
                        roadSide = 1; lane = 2; sfx.play(Sfx.SWOOSH); sfx.buzz(Buzz.LIGHT)
                    }
                    lane > 0 -> { lane--; sfx.play(Sfx.SWOOSH) }
                    roadSide != 0 -> sfx.play(Sfx.UI)
                }
                Input.RIGHT -> if (inputEnabled) when {
                    roadSide == 1 && lane == 2 && envAt(scroll) < GameConfig.CROSS_MAX_ENV -> {
                        roadSide = 2; lane = 0; sfx.play(Sfx.SWOOSH); sfx.buzz(Buzz.LIGHT)
                    }
                    lane < laneMax() -> { lane++; sfx.play(Sfx.SWOOSH) }
                    roadSide != 0 -> sfx.play(Sfx.UI)
                }
                Input.JUMP -> if (inputEnabled) {
                    jumpBuf = 0.14f
                    if (grounded && !gameOver) { jumpBuf = 0f; doJump(jumpV()) }
                }
                Input.DIVE -> if (inputEnabled && !grounded) vy = -20f
            }
        }
    }

    private fun doJump(v: Float) {
        vy = v; grounded = false
        pressure[Traits.P_SPRING] += 0.35f
        sfx.play(Sfx.JUMP)
        repeat(5) { burstAt(playerX, 0.3f, 0f) }
    }

    // ---------------- simulation ----------------

    private fun sim(dt: Float) {
        t += dt
        magnetT = (magnetT - dt).coerceAtLeast(0f)
        overT = (overT - dt).coerceAtLeast(0f)
        invuln = (invuln - dt).coerceAtLeast(0f)
        flash = (flash - dt * 2.5f).coerceAtLeast(0f)
        shake = max(0f, shake - dt * 1.8f)
        squash = max(0f, squash - dt * 3.5f)
        announceT = (announceT - dt).coerceAtLeast(0f)
        cleanT += dt
        styleT -= dt
        if (styleT <= 0f) styleCombo = 0
        comboT -= dt
        if (comboT <= 0f) combo = 0

        val dz0 = (if (gameOver) speed else speed * (if (overT > 0f) 1.3f else 1f)) * dt
        updateParticles(dt, dz0)

        if (gameOver) {
            speed = max(0f, speed - 26f * dt)
            scroll += dz0
            for (o in obstacles) o.z += dz0
            for (o in orbs) o.z += dz0
            for (g in gates) g.z += dz0
            return
        }

        speed = min(speedCap(), speed + GameConfig.ACCEL * Traits.accelMult(traits) * dt)
        val dz = speed * (if (overT > 0f) 1.3f else 1f) * dt
        distance += dz; scroll += dz
        // speed pays: above 24 m/s the score rate climbs
        score += dz * 0.5f * (if (overT > 0f) 2f else 1f) *
            (1f + max(0f, speed - 24f) * GameConfig.SPEED_SCORE_K)
        evoCharge = (evoCharge + dz * 0.0022f).coerceAtMost(1f)

        // milestones
        if (distance >= nextMilestone) {
            val pay = 50f * styleMult
            score += pay
            evoCharge = (evoCharge + 0.25f).coerceAtMost(1f)
            announce("MILESTONE ${nextMilestone.toInt()}m  +${pay.toInt()}", 1.8f, 1)
            sfx.play(Sfx.POWER)
            nextMilestone += GameConfig.MILESTONE_STEP
        }

        // ---- road-side state machine (fork entry / exit) ----
        val envNow = envAt(scroll)
        if (envNow > 0.02f && roadSide == 0) {
            fun bestLane(side: Int): Int =
                ((playerX - sideOffAt(scroll, side)) / GameConfig.LANE_W)
                    .roundToInt().coerceIn(0, 2)
            val l0 = bestLane(1); val r0 = bestLane(2)
            val lx = sideOffAt(scroll, 1) + (l0 - 1) * GameConfig.LANE_W
            val rx = sideOffAt(scroll, 2) + (r0 - 1) * GameConfig.LANE_W
            roadSide = when {
                playerX < -0.8f -> 1
                playerX > 0.8f -> 2
                abs(playerX - lx) <= abs(playerX - rx) -> 1
                else -> 2
            }
            lane = if (roadSide == 1) l0 else r0
            activeSpan = forks.firstOrNull { it.contains(scroll) }
            currentRisky = activeSpan?.riskySide == roadSide
            val lbl = if (roadSide == 1) activeSpan?.labelL else activeSpan?.labelR
            sfx.play(Sfx.SWOOSH); sfx.buzz(Buzz.LIGHT)
            announce("TOOK ${if (roadSide == 1) "LEFT" else "RIGHT"} · $lbl", 2.0f, 2)
            telemetry.event("fork_pick", mapOf(
                "side" to (if (roadSide == 1) "L" else "R"), "risky" to currentRisky))
        } else if (envNow < 0.02f && roadSide != 0) {
            val wasRisky = currentRisky
            roadSide = 0; activeSpan = null; currentRisky = false
            lane = ((playerX / GameConfig.LANE_W).roundToInt() + 2)
                .coerceIn(0, GameConfig.LANES - 1)
            score += 40f
            if (wasRisky) {
                score += GameConfig.RISK_CLEAR_BONUS
                styleAward(30f)
                pressure[Traits.P_FERAL] += 0.5f
                pressure[Traits.P_HUNGER] += 0.5f
                announce("RISKY ROAD CLEARED", 2.0f, 2)
            } else {
                announce("FORK CLEARED +40", 1.6f, 1)
            }
            sfx.play(Sfx.WHOOSH); sfx.buzz(Buzz.LIGHT)
        }

        playerX += (targetPlayerX() - playerX) * min(1f, dt * 11f)

        if (!grounded) {
            vy -= GameConfig.GRAVITY * dt
            jumpY += vy * dt
            if (jumpY <= 0f) {
                val fall = -vy
                jumpY = 0f; vy = 0f; grounded = true
                squash = (fall / 20f).coerceIn(0.15f, 1f)
                if (fall > 4f && abs(playerX - targetPlayerX()) < GameConfig.PERFECT_X) {
                    styleAward(10f)
                    evoCharge = (evoCharge + 0.05f).coerceAtMost(1f)
                    sfx.play(Sfx.PERFECT)
                } else {
                    sfx.play(Sfx.LAND)
                }
                repeat(4) { burstAt(playerX, 0.2f, 0f) }
                if (fall > GameConfig.SLAM_FALL_V) slam()
                if (jumpBuf > 0f) { jumpBuf = 0f; doJump(jumpV()) }
            }
        }
        jumpBuf = (jumpBuf - dt).coerceAtLeast(0f)

        if (fx.phase == EvolutionFx.Phase.CHARGE) {
            chargeAcc += dt * 26f
            while (chargeAcc >= 1f) { chargeAcc -= 1f; converge() }
        }

        trailAcc += dt * speed * 0.55f
        while (trailAcc > 1f) { trailAcc -= 1f; spawnTrail() }
        if (speed > 24f || overT > 0f) {
            streakAcc += dt * (speed - 20f) * (if (overT > 0f) 2.2f else 1f)
            while (streakAcc > 1f) { streakAcc -= 1f; spawnStreak() }
        }

        if (distance >= sectionEnd) pickSection()
        if (forkAnnounceD > 0f && distance >= forkAnnounceD) {
            announce("PATH FORK AHEAD", 2.4f, 2)
            forkAnnounceD = -1f
        }
        if (distance + GameConfig.SPAWN_HORIZON >= nextForkD) createFork()
        forks.removeAll { it.mergeD < scroll - 40f }

        if (distance >= forkHoldUntil) {
            distSinceRow += dz
            if (distSinceRow >= rowGap) {
                distSinceRow = 0f
                val len = spawnChunk(GameConfig.SPAWN_Z)
                rowGap = (len + 16f + rng.nextFloat() * 16f - distance / 4000f).coerceAtLeast(22f)
            }
        }
        if (distance >= nextCheckpoint) {
            var gd = nextCheckpoint
            for (f in forks) if (gd > f.forkD - 30f && gd < f.mergeD + 30f) gd = f.mergeD + 40f
            gates.add(Gate(scroll - gd, false))
            nextCheckpoint = gd + GameConfig.CHECKPOINT_EVERY
        }

        for (o in obstacles) o.z += dz
        for (o in orbs) o.z += dz
        for (g in gates) g.z += dz
        obstacles.removeAll { it.z - it.len * 0.5f > GameConfig.DESPAWN_Z }
        orbs.removeAll { it.z > GameConfig.DESPAWN_Z || it.taken }
        gates.removeAll { it.z > GameConfig.DESPAWN_Z }

        while (nextDecorD < scroll + GameConfig.SPAWN_HORIZON + 10f) {
            nextDecorD += 8f + rng.nextFloat() * 9f
            val side = if (rng.nextBoolean()) 1f else -1f
            when (rng.nextInt(12)) {
                0, 1, 2 -> decor.add(Decor(nextDecorD, 0, side * (7.4f + rng.nextFloat() * 2.6f), 2.2f + rng.nextFloat() * 3f))
                3 -> decor.add(Decor(nextDecorD, 1, 0f, 1f))
                4, 5 -> decor.add(Decor(nextDecorD, 2, side * (6.6f + rng.nextFloat() * 3.2f), 0.7f + rng.nextFloat() * 1.5f))
                6 -> decor.add(Decor(nextDecorD, 3, side * (6.8f + rng.nextFloat() * 3f), 0.9f + rng.nextFloat() * 1.4f))
                7, 8 -> decor.add(Decor(nextDecorD, 4, side * (7.6f + rng.nextFloat() * 2.4f), 4.5f + rng.nextFloat() * 3f))
                9 -> decor.add(Decor(nextDecorD, 5, side * (6.0f + rng.nextFloat() * 2.6f), 1f))
                10 -> {
                    decor.add(Decor(nextDecorD, 4, 7.6f, 4.6f + rng.nextFloat() * 2f))
                    decor.add(Decor(nextDecorD, 4, -7.6f, 4.6f + rng.nextFloat() * 2f))
                }
                else -> { }
            }
        }
        decor.removeAll { it.d < scroll - 30f }

        collide()
        magnet(dt)
        nearMiss()

        for (g in gates) if (!g.passed && g.z > 0f) { g.passed = true; onCheckpoint() }

        val decay = exp(-dt * 0.12f)
        pressureDamage *= decay
        pressureOrbs *= decay

        evolveBlend()
    }

    /** Dive-slam: impact shockwave vacuums nearby orbs. */
    private fun slam() {
        var n = 0
        for (o in orbs) {
            if (o.taken || !applies(o.side) || abs(o.z) > GameConfig.SLAM_RADIUS_Z) continue
            val ox = o.x + sideOffAt(scroll - o.z, o.side)
            if (abs(ox - playerX) > GameConfig.SLAM_RADIUS_X) continue
            o.taken = true
            n++
            styleAward(8f)
            evoCharge = (evoCharge + 0.04f).coerceAtMost(1f)
            repeat(3) { burstAt(o.x, o.y, o.z, o.side) }
        }
        squash = 1f
        shake = max(shake, 0.4f)
        hitStop = max(hitStop, 0.06f)
        pressure[Traits.P_SPRING] += 0.5f
        sfx.play(Sfx.SMASH); sfx.buzz(Buzz.LIGHT)
        if (n > 0) announce("SLAM ×$n", 1.2f, 0)
    }

    // ---------------- sectors ----------------

    private fun pickSection() {
        section = if (distance < 250f) 0 else rng.nextInt(5)
        sectionEnd = distance + 260f + rng.nextFloat() * 220f
        val nm = when (section) {
            1 -> "CHASM FIELDS"; 2 -> "TUNNELS"; 3 -> "PILLAR MAZE"
            4 -> "VELOCITY STRAIT"; else -> "OPEN ROAD"
        }
        announce("SECTOR · $nm", 2.0f, 1)
        telemetry.event("sector", mapOf("name" to nm, "dist" to distance.toInt()))
    }

    // ---------------- path forks (personalities) ----------------

    private fun createFork() {
        val forkD = nextForkD
        val mergeD = forkD + 150f + rng.nextFloat() * 80f
        forkHoldUntil = mergeD + 30f
        nextForkD = mergeD + 420f + rng.nextFloat() * 280f
        forkAnnounceD = forkD - 70f

        obstacles.removeAll { it.side == 0 &&
            (scroll - it.z) > forkD - 45f && (scroll - it.z) < mergeD + 12f }
        orbs.removeAll { it.side == 0 &&
            (scroll - it.z) > forkD - 45f && (scroll - it.z) < mergeD + 12f }

        val safe = if (rng.nextBoolean()) 1 else 2
        val risk = 3 - safe
        val safeKind = rng.nextInt(2)      // 0 ORBS · 1 CALM
        val riskKind = rng.nextInt(2)      // 0 GAUNTLET · 1 VOID RUN
        val safeLbl = if (safeKind == 0) "ORBS" else "CALM"
        val riskLbl = if (riskKind == 0) "GAUNTLET" else "VOID RUN"
        forks.add(ForkSpan(forkD, mergeD, risk,
            if (safe == 1) safeLbl else riskLbl,
            if (safe == 2) safeLbl else riskLbl))

        for (k in 0..2) {
            branchOrb(1, 1, 1.0f, forkD + 6f + k * 4f)
            branchOrb(2, 1, 1.0f, forkD + 6f + k * 4f)
        }

        when (safeKind) {
            0 -> {
                val sl = rng.nextInt(3)
                for (k in 0..4) branchOrb(safe, sl, 1.0f, forkD + 26f + k * 5f)
                branchObs(safe, (sl + 1 + rng.nextInt(2)) % 3, 1, forkD + 46f)
                for (k in 0..2) branchOrb(safe, sl, 1.0f, forkD + 56f + k * 5f)
                if (rng.nextBoolean()) branchPower(safe, rng.nextInt(3), mergeD - 34f)
            }
            else -> {
                branchObs(safe, rng.nextInt(3), 3, forkD + 30f)
                for (k in 0..2) branchOrb(safe, 1, 1.0f, forkD + 40f + k * 6f)
                branchOrb(safe, rng.nextInt(3), 1.6f, mergeD - 34f, 3)
            }
        }
        when (riskKind) {
            0 -> {
                var g = rng.nextInt(3)
                for (r in 0..1) {
                    for (l in 0..2) if (l != g) branchObs(risk, l, 0, forkD + 30f + r * 22f)
                    branchOrb(risk, g, 1.0f, forkD + 33f + r * 22f)
                    g = (g + if (rng.nextBoolean()) 1 else 2) % 3
                }
                branchChasm(risk, mergeD - 44f)
                branchObs(risk, rng.nextInt(3), 1, mergeD - 28f)
                branchPower(risk, rng.nextInt(3), mergeD - 20f)
            }
            else -> {
                branchChasm(risk, forkD + 40f)
                branchArc(risk, rng.nextInt(3), forkD + 34f)
                branchChasm(risk, mergeD - 46f)
                branchArc(risk, rng.nextInt(3), mergeD - 52f)
                branchOrb(risk, rng.nextInt(3), 1.6f, (forkD + mergeD) * 0.5f, 1)
                branchPower(risk, rng.nextInt(3), mergeD - 20f)
            }
        }

        for (k in 0..1) {
            branchOrb(1, 1, 1.0f, mergeD - 12f + k * 4f)
            branchOrb(2, 1, 1.0f, mergeD - 12f + k * 4f)
        }
        telemetry.event("fork_created", mapOf(
            "forkD" to forkD.toInt(), "risk" to riskLbl, "safe" to safeLbl))
    }

    private fun branchObs(side: Int, laneB: Int, kind: Int, d: Float) {
        val o = Obstacle(laneB, kind, scroll - d, 0f, side)
        o.phase = rng.nextFloat() * 6.28f
        obstacles.add(o)
    }

    private fun branchChasm(side: Int, d: Float) {
        obstacles.add(Obstacle(1, 4, scroll - d, GameConfig.CHASM_LEN, side))
    }

    private fun branchOrb(side: Int, laneB: Int, y: Float, d: Float, kind: Int = 0) {
        orbs.add(Orb((laneB - 1) * GameConfig.LANE_W, y, scroll - d, kind, side))
    }

    private fun branchPower(side: Int, laneB: Int, d: Float) {
        branchOrb(side, laneB, 1.6f, d, if (rng.nextBoolean()) 1 else 2)
    }

    private fun branchArc(side: Int, laneB: Int, d0: Float) {
        val xs = floatArrayOf(-0.45f, 0f, 0.45f, 0f)
        val ys = floatArrayOf(1.4f, 2.0f, 2.0f, 1.4f)     // reachable at normal jump apex
        for (k in 0..3) {
            orbs.add(Orb((laneB - 1) * GameConfig.LANE_W + xs[k], ys[k],
                scroll - (d0 + k * 2.2f), 0, side))
        }
    }

    // ---------------- staged gene expression ----------------

    private fun evolveBlend() {
        if (!fx.active) return
        val a = startGenes; val b = targetGenes
        val cb = fx.colorBlend(); val sb = fx.shapeBlend(); val wb = fx.weightBlend()

        var k = 0
        while (k < Genetics.N_GRID) { genes[k] = a[k] + (b[k] - a[k]) * sb; k++ }
        while (k < 2 * Genetics.N_GRID) { genes[k] = a[k] + (b[k] - a[k]) * wb; k++ }
        k = Genetics.STRETCH
        while (k <= Genetics.WSPEED) { genes[k] = a[k] + (b[k] - a[k]) * sb; k++ }
        k = Genetics.HUE_A
        while (k <= Genetics.TRAIL) { genes[k] = a[k] + (b[k] - a[k]) * cb; k++ }
        Genetics.clampAll(genes)
        sizeFactor = Genetics.sizeFactor(genes)

        if (abs(sb - lastSb) > 1e-4f || abs(wb - lastWb) > 1e-4f) {
            meshDirty = true; lastSb = sb; lastWb = wb
        }
    }

    // ---------------- interactions ----------------

    /** Clean relevance rule: an entity applies only to the road you're on. */
    private fun applies(oSide: Int): Boolean =
        (oSide == 0 && roadSide == 0) || (oSide != 0 && oSide == roadSide)

    private fun collide() {
        val s = sizeFactor
        val cy = 0.85f * s + jumpY
        val hx = 0.5f * s; val hy = 0.6f * s; val hz = 0.8f * s
        val hb = hangBottom()

        for (o in orbs) {
            if (o.taken || !applies(o.side) || abs(o.z) > 1.6f) continue
            val ox = o.x + sideOffAt(scroll - o.z, o.side)
            val dx = playerX - ox; val dyy = cy - o.y
            val rr = 0.55f * s + 0.45f
            if (dx * dx + dyy * dyy + o.z * o.z < rr * rr) collectOrb(o)
        }

        for (o in obstacles) {
            if (o.used || !applies(o.side)) continue
            val ox = o.xAt(t) + sideOffAt(scroll - o.z, o.side)
            when (o.kind) {
                2 -> {
                    if (abs(o.z) > 1.0f || abs(playerX - ox) > 1.0f) continue
                    o.used = true
                    if (grounded) {
                        doJump(padJumpV())
                        pressure[Traits.P_SPRING] += 1f
                        score += 10f
                        evoCharge = (evoCharge + 0.05f).coerceAtMost(1f)
                    }
                }
                3 -> {
                    if (abs(o.z) > 1.0f || abs(playerX - ox) > 1.0f) continue
                    o.used = true
                    speed = min(speedCap(), speed * 1.15f + 2f)
                    invuln = max(invuln, GameConfig.BOOST_IFRAMES)   // no cheap boost-into-wall
                    pressure[Traits.P_SWIFT] += 1f
                    evoCharge = (evoCharge + 0.10f).coerceAtMost(1f)
                    flash = max(flash, 0.35f)
                    sfx.play(Sfx.BOOST)
                    announce("BOOST!", 1.4f, 0)
                }
                4 -> {
                    val half = o.len * 0.5f
                    if (abs(o.z) < half &&
                        abs(playerX - ox) < 1.1f &&
                        jumpY < GameConfig.CHASM_CLEAR_Y && invuln <= 0f) {
                        voidDeath(); return
                    }
                    if (!o.scored && o.z > half + 0.5f) {
                        o.scored = true
                        if (abs(playerX - ox) < 1.3f) {
                            styleAward(8f + speed * 0.35f)           // speed = bigger clear pay
                            pressure[Traits.P_SPRING] += 0.8f
                            sfx.play(Sfx.WHOOSH)
                        }
                    }
                }
                5 -> {
                    if (abs(o.z) < hz + 0.6f &&
                        abs(playerX - ox) < 1.1f &&
                        cy + hy > hb && invuln <= 0f) {
                        hit("TUNNEL")
                    }
                }
            }
        }

        if (overT > 0f) {
            val it = obstacles.iterator()
            while (it.hasNext()) {
                val o = it.next()
                if (o.kind != 0 || !applies(o.side)) continue
                val ox = o.xAt(t) + sideOffAt(scroll - o.z, o.side)
                if (abs(o.z) < hz + 0.6f && abs(playerX - ox) < hx + 0.9f && cy - hy < 2.6f) {
                    it.remove(); score += 50f; shake = max(shake, 0.3f)
                    hitStop = max(hitStop, 0.05f)                     // smash impact
                    squash = max(squash, 0.6f)
                    pressure[Traits.P_FERAL] += 0.35f
                    repeat(10) { burstAt(ox, 1.2f, o.z) }
                    sfx.play(Sfx.SMASH); sfx.buzz(Buzz.LIGHT)
                }
            }
        } else if (invuln <= 0f) {
            for (o in obstacles) {
                if ((o.kind != 0 && o.kind != 1) || !applies(o.side)) continue
                val ox = o.xAt(t) + sideOffAt(scroll - o.z, o.side)
                val h = if (o.kind == 0) 2.6f else 0.7f
                if (abs(o.z) < hz + 0.6f &&
                    abs(playerX - ox) < hx + 0.8f &&
                    cy - hy < h) { hit(if (o.kind == 0) "WALL" else "BAR"); break }
            }
        }
    }

    private fun nearMiss() {
        val hx = 0.5f * sizeFactor
        for (o in obstacles) {
            if (o.scored || o.kind == 4 || o.kind == 5 || o.z <= 1.2f) continue
            if ((o.kind != 0 && o.kind != 1) || !applies(o.side)) continue
            o.scored = true
            val ox = o.xAt(t) + sideOffAt(scroll - o.z, o.side)
            val dx = abs(playerX - ox)
            var awarded = false
            if (o.kind == 1 && jumpY > 0.4f && dx < hx + 0.9f) {
                styleAward(6f)
                pressureDamage = (pressureDamage - 0.10f).coerceAtLeast(0f)
                awarded = true
            } else if (dx in (hx + 0.8f)..(hx + 1.3f)) {
                styleAward(4f)
                pressureDamage = (pressureDamage - 0.05f).coerceAtLeast(0f)
                awarded = true
            }
            if (awarded) {
                pressure[Traits.P_HIDE] = (pressure[Traits.P_HIDE] - 0.15f).coerceAtLeast(0f)
                sfx.play(Sfx.WHOOSH)
            }
        }
    }

    private fun magnet(dt: Float) {
        val passiveR = Traits.magnetRadius(traits)
        if (magnetT <= 0f && passiveR < 0.05f) return
        val r = if (magnetT > 0f) 8f else passiveR
        val k = min(1f, dt * (if (magnetT > 0f) 6f else 2.5f))
        val cy = 0.85f * sizeFactor + jumpY
        for (o in orbs) {
            if (o.taken || !applies(o.side) || abs(o.z) > r) continue
            val ox = o.x + sideOffAt(scroll - o.z, o.side)
            o.x += (playerX - ox) * k
            o.y += (cy - o.y) * k
        }
    }

    private fun collectOrb(o: Orb) {
        o.taken = true
        when (o.kind) {
            0 -> {
                combo++; comboT = 2.5f
                val od = scroll - o.z
                val span = forks.firstOrNull { od > it.forkD && od < it.mergeD }
                val risky = span != null && o.side == span.riskySide
                val base = 25f * Traits.orbMult(traits) * min(5, 1 + combo / 5)
                score += if (risky) base * GameConfig.RISK_ORB_MULT else base
                evoCharge = (evoCharge + 0.12f).coerceAtMost(1f)
                pressureOrbs = min(2f, pressureOrbs + 0.4f)
                pressure[Traits.P_HUNGER] += if (risky) 0.30f else 0.22f
                if (combo >= 3) pressure[Traits.P_PULL] += 0.08f
                sfx.play(Sfx.ORB)
            }
            1 -> { magnetT = 8f; pressure[Traits.P_PULL] += 1f
                announce("MAGNET FIELD", 1.6f, 0); powerFeedback() }
            2 -> { overT = 6f * Traits.overdriveMult(traits); pressure[Traits.P_FERAL] += 1f
                announce("OVERDRIVE!", 1.6f, 0); powerFeedback() }
            else -> {
                if (shield < GameConfig.MAX_SHIELD) shield++
                announce("SHIELD +1", 1.6f, 0); powerFeedback()
            }
        }
        repeat(6) { burstAt(o.x, o.y, o.z, o.side) }
    }

    private fun powerFeedback() { sfx.play(Sfx.POWER); sfx.buzz(Buzz.LIGHT) }

    private fun hit(cause: String) {
        combo = 0
        styleCombo = 0                              // style resets on hit
        cleanT = 0f
        hitsSinceCP++
        shake = max(shake, 0.6f)
        squash = max(squash, 0.7f)
        hitStop = max(hitStop, 0.09f)               // hit impact
        speed *= 0.75f
        pressure[Traits.P_HIDE] += 1f
        if (shield > 0) {
            hitStreak++
            val per = Traits.hitsPerShield(traits)
            if (hitStreak < per) {
                invuln = 1.2f; flash = 1f
                sfx.play(Sfx.HIT)
                announce("ARMOR HELD", 1.6f, 1)
                return
            }
            hitStreak = 0
            shield--; invuln = 1.2f; flash = 1f
            pressureDamage += 0.5f
            sfx.play(Sfx.HIT); sfx.buzz(Buzz.MEDIUM)
            if (shield == 0) sfx.play(Sfx.WARN)
            announce("SHIELD LOST", 2.0f, 2)
        } else {
            die(cause)
        }
    }

    private fun voidDeath() {
        flash = 1f; shake = 1f
        hitStop = 0.22f
        die("VOID")
    }

    /** High-fitness creatures refuse to die — once per run. */
    private fun trySecondWind(): Boolean {
        if (usedWind || lastFitness < GameConfig.WIND_FIT) {
            // no free path — offer the ad revive if available (once per run)
            reviveOffer = !usedWind && ads.isRewardedReady()
            return false
        }
        usedWind = true
        reviveOffer = false
        performRevive("SECOND WIND — THE LINEAGE REFUSES")
        return true
    }

    private fun doAdRevive() {
        usedWind = true
        reviveOffer = false
        performRevive("SECOND WIND — THE LINEAGE BARGAINS")
    }

    private fun performRevive(banner: String) {
        gameOver = false
        branches = null
        shield = 1
        invuln = 2.5f
        flash = 1f; shake = 0.8f
        hitStop = 0.18f
        obstacles.removeAll { abs(it.z) < 30f }     // vaporize the immediate threat
        announce(banner, 2.8f, 3)
        sfx.play(Sfx.EVOLVE); sfx.buzz(Buzz.HEAVY)
        telemetry.event("revive", mapOf(
            "dist" to distance.toInt(), "fit" to lastFitness))
    }

    private fun die(cause: String) {
        if (trySecondWind()) return
        gameOver = true
        hitStop = max(hitStop, 0.22f)
        pressureDamage += 1f
        lineage.record(genes, lastFitness)
        lineage.deaths++
        lineage.bestDist = max(lineage.bestDist, distance)
        lineage.recordDeath(cause, distance.toInt(), generation, lineage.describe(genes))
        lineage.recordRun(distance.toInt(), score.toInt(), generation)
        sfx.play(Sfx.DEATH); sfx.buzz(Buzz.HEAVY)
        telemetry.event("death", mapOf(
            "cause" to cause, "distance" to distance.toInt(),
            "score" to score.toInt(), "gen" to generation))
        buildBranches()
        requestSave()
        announce("SYSTEM FAILURE", 3f, 3)
    }

    // ---------------- divergent respawn (bloodline) ----------------

    private fun buildBranches() {
        val base = (lineage.best ?: genes.copyOf())
        val list = ArrayList<BranchCandidate>(3)

        val tA = Traits.evolve(traits.copyOf(), FloatArray(Traits.N), 0.4f)
        val tB = Traits.evolve(traits.copyOf(), FloatArray(Traits.N), 2.0f)
        val tC = FloatArray(Traits.N) { (traits[it] + Traits.defaults()[it]) * 0.5f }
        Traits.evolve(tC, FloatArray(Traits.N), 1.0f)

        list.add(BranchCandidate(Genetics.mutate(base.copyOf(), 0.8f), tA,
            Traits.branchLabel(tA), lineage.describe(base)))
        list.add(BranchCandidate(Genetics.mutate(base.copyOf(), 2.4f), tB,
            Traits.branchLabel(tB), lineage.describe(base)))
        val hybrid = Genetics.crossover(base, Genetics.randomGenome())
        list.add(BranchCandidate(Genetics.mutate(hybrid, 1.2f), tC,
            Traits.branchLabel(tC), lineage.describe(hybrid)))

        branches = list
    }

    // ---------------- checkpoint -> metamorphosis + heredity ----------------

    private fun onCheckpoint() {
        generation++
        lineage.evolutions++
        envBias = (pressureOrbs - pressureDamage).coerceIn(-1f, 1f)
        val parentFit = Genetics.fitness(targetGenes, envBias)
        lineage.recordAncestor(genes.copyOf(), generation - 1, parentFit, envBias)

        val gens = 6 + (evoCharge * 8f).toInt()
        val (best, fit, sp) = Genetics.evolve(targetGenes, envBias, gens)
        val wasBest = fit > lineage.bestFit
        lineage.record(best, fit)
        startGenes = genes.copyOf(); targetGenes = best
        lastFitness = fit; lastDelta = fit - parentFit; lastSpecies = sp

        val pNorm = FloatArray(Traits.N) { pressure[it].coerceAtMost(3f) / 3f }
        traits = Traits.evolve(traits, pNorm)
        lineage.traits = traits.copyOf()
        for (i in pressure.indices) pressure[i] *= 0.5f

        ghostGenes = startGenes.copyOf()
        ghostDirty = true

        pendingScore = 150f
        if (hitsSinceCP == 0) {
            pendingScore += 100f
            pendingMsg = "FLAWLESS · "
        }
        hitsSinceCP = 0
        pendingShield = shield < GameConfig.MAX_SHIELD
        val label = when {
            envBias < -0.25f -> "STREAMLINE"; envBias > 0.25f -> "BULK UP"; else -> "NEUTRAL"
        }
        pendingMsg += "%sGEN %d · FIT %.2f (%+.2f) · %d SP · %s"
            .format(if (wasBest) "★ " else "", generation, fit, lastDelta, sp, label)
        evoCharge = 0f
        lastSb = 0f; lastWb = 0f
        sfx.play(Sfx.CHARGE); sfx.buzz(Buzz.MEDIUM)
        telemetry.event("checkpoint", mapOf(
            "gen" to generation, "fit" to fit, "species" to sp,
            "bias" to envBias, "traits" to Traits.describe(traits)))
        requestSave()
        fx.start()
    }

    // ---------------- particles (pooled, quality-capped, road-aware) ----------------

    private fun obtain(): Particle? =
        pool.removeFirstOrNull() ?: (if (trail.size < settings.particleCap) Particle() else null)

    private fun updateParticles(dt: Float, dz: Float) {
        val it = trail.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            p.x += p.vx * dt; p.y += p.vy * dt; p.vy -= 3f * dt
            p.z += dz * 0.9f + p.vz * dt
            if (p.life <= 0f || p.z > 14f) { it.remove(); pool.addLast(p) }
        }
    }

    private fun spawnTrail() {
        val s = sizeFactor
        val cy = 0.85f * s + jumpY
        val p = obtain() ?: return
        val life = 0.45f + rng.nextFloat() * 0.35f
        p.x = playerX + (rng.nextFloat() - 0.5f) * 0.5f * s
        p.y = cy + (rng.nextFloat() - 0.5f) * 0.7f * s
        p.z = 0.95f * genes[Genetics.STRETCH] + (rng.nextFloat() - 0.5f) * 0.4f
        p.vx = (rng.nextFloat() - 0.5f) * 0.8f
        p.vy = (rng.nextFloat() - 0.2f) * 1.2f
        p.vz = 0.5f + rng.nextFloat()
        p.life = life; p.maxLife = life
        p.size = (0.09f + rng.nextFloat() * 0.07f) * s
        p.stretch = 1f; p.side = roadSide
        trail.add(p)
    }

    private fun burstAt(x: Float, y: Float, z: Float, side: Int = roadSide) {
        val p = obtain() ?: return
        val life = 0.4f + rng.nextFloat() * 0.35f
        p.x = x; p.y = y; p.z = z
        p.vx = (rng.nextFloat() - 0.5f) * 4f
        p.vy = rng.nextFloat() * 3f
        p.vz = (rng.nextFloat() - 0.3f) * 3f
        p.life = life; p.maxLife = life
        p.size = 0.08f + rng.nextFloat() * 0.06f
        p.stretch = 1f; p.side = side
        trail.add(p)
    }

    private fun converge() {
        val p = obtain() ?: return
        val s = sizeFactor
        val R = 2.2f + rng.nextFloat() * 1.6f
        val th = rng.nextFloat() * 6.2832f
        val ph = (rng.nextFloat() - 0.5f) * 3.1416f
        val cy = 0.85f * s + jumpY
        val dx = cos(th) * cos(ph); val dy = sin(ph); val dzz = sin(th) * cos(ph)
        val life = 0.45f
        p.x = playerX + dx * R
        p.y = (cy + dy * R).coerceAtLeast(0.1f)
        p.z = dzz * R
        p.vx = -dx * R / life; p.vy = -dy * R / life; p.vz = -dzz * R / life
        p.life = life; p.maxLife = life
        p.size = 0.10f + rng.nextFloat() * 0.05f
        p.stretch = 1f; p.side = roadSide
        trail.add(p)
    }

    private fun explode() {
        val p = obtain() ?: return
        val cy = 0.85f * sizeFactor + jumpY
        val th = rng.nextFloat() * 6.2832f
        val ph = (rng.nextFloat() - 0.5f) * 3f
        val sp = 2.5f + rng.nextFloat() * 3.5f
        p.x = playerX; p.y = cy; p.z = 0f
        p.vx = cos(th) * cos(ph) * sp
        p.vy = sin(ph) * sp + 1f
        p.vz = sin(th) * cos(ph) * sp
        p.life = 0.6f + rng.nextFloat() * 0.3f; p.maxLife = p.life
        p.size = 0.10f + rng.nextFloat() * 0.08f
        p.stretch = 1f; p.side = roadSide
        trail.add(p)
    }

    private fun ringSparkle() {
        val cy = 0.85f * sizeFactor
        for (k in 0 until 18) {
            val p = obtain() ?: return
            val a = 6.2832f * k / 18f
            p.x = playerX + cos(a) * 0.7f
            p.y = cy
            p.z = sin(a) * 0.7f
            p.vx = cos(a) * 2.2f; p.vy = 0.8f + rng.nextFloat(); p.vz = sin(a) * 2.2f
            p.life = 0.5f; p.maxLife = 0.5f
            p.size = 0.09f; p.stretch = 1f; p.side = roadSide
            trail.add(p)
        }
    }

    private fun spawnStreak() {
        val p = obtain() ?: return
        p.x = (rng.nextFloat() - 0.5f) * 18f
        p.y = 0.5f + rng.nextFloat() * 6f
        p.z = -70f - rng.nextFloat() * 30f
        p.vx = 0f; p.vy = 0f; p.vz = 0f
        p.life = 0.6f; p.maxLife = 0.6f
        p.size = 0.05f + rng.nextFloat() * 0.04f
        p.stretch = 8f; p.side = 0
        trail.add(p)
    }

    // ---------------- 16 patterns + tension director ----------------

    private fun pickPattern(): Int {
        var pool = ArrayList<Int>(16)
        when (section) {
            1 -> { pool += intArrayOf(8, 8, 9, 0, 7) }
            2 -> { pool += intArrayOf(10, 10, 11, 7) }
            3 -> { pool += intArrayOf(1, 4, 12, 13, 13) }
            4 -> { pool += intArrayOf(5, 6, 6, 15, 3) }
            else -> { pool += intArrayOf(0, 1, 2, 3, 4, 5, 6, 7) }
        }
        if (distance > 300f) pool += intArrayOf(8, 9, 10)
        if (distance > 700f) pool += intArrayOf(11, 12, 13, 14, 15, 8, 9)

        // tension director: never more than N hard chunks back-to-back
        if (hardStreak >= GameConfig.TENSION_STREAK) {
            val calm = ArrayList(pool.filter { PATTERN_COST[it] <= 0.4f })
            if (calm.isNotEmpty()) pool = calm
        }
        val p = pool[rng.nextInt(pool.size)]
        if (PATTERN_COST[p] > 0.55f) hardStreak++ else hardStreak = 0
        return p
    }

    private fun spawnChunk(z0: Float): Float {
        val diff = (distance / GameConfig.DIFF_RAMP_DIST).coerceIn(0f, 1f)
        val L = GameConfig.LANES
        fun obs(l: Int, kind: Int, off: Float, drift: Float = 0f) {
            val o = Obstacle(l, kind, z0 - off)
            o.drift = drift; o.phase = rng.nextFloat() * 6.28f
            obstacles.add(o)
        }
        fun chasm(l: Int, off: Float, len: Float = GameConfig.CHASM_LEN) {
            obstacles.add(Obstacle(l, 4, z0 - off, len))
        }
        fun hang(l: Int, off: Float) { obs(l, 5, off) }
        fun orb(x: Float, y: Float, off: Float, kind: Int = 0) { orbs.add(Orb(x, y, z0 - off, kind)) }
        fun orbLine(l: Int, y: Float, n: Int, off0: Float, kind: Int = 0) {
            for (k in 0 until n) orb(laneX(l), y, off0 + k * 3.2f, kind)
        }
        fun orbArc(l: Int, off0: Float) {
            val xs = floatArrayOf(-0.45f, 0f, 0.45f, 0f)
            val ys = floatArrayOf(1.4f, 2.0f, 2.0f, 1.4f)
            for (k in 0..3) orb(laneX(l) + xs[k], ys[k], off0 + k * 2.2f)
        }
        fun powerup(off: Float) {
            val r = rng.nextFloat()
            val kind = if (r < 0.4f) 1 else if (r < 0.75f) 2 else 3
            orb(laneX(rng.nextInt(L)), 1.6f, off, kind)
        }
        val dr = if (distance > GameConfig.DRIFT_AFTER &&
            rng.nextFloat() < 0.35f) GameConfig.DRIFT_AMPLITUDE else 0f

        return when (pickPattern()) {
            0 -> { val gap = rng.nextInt(L)
                for (l in 0 until L) if (l != gap) obs(l, 0, 0f, dr)
                orbLine(gap, 1.0f, 4, 2f)
                12f }
            1 -> { var l = rng.nextInt(L)
                for (k in 0..2) {
                    obs(l, 0, k * 14f, dr)
                    l = (l + if (rng.nextBoolean()) 1 else L - 1) % L
                }
                30f }
            2 -> { val l = rng.nextInt(L)
                obs(l, 1, 0f); orb(laneX(l), 2.1f, 0f)
                orbLine((l + 1) % L, 1.0f, 3, 3f)
                12f }
            3 -> { val l = rng.nextInt(L)
                orbLine(l, 1.0f, 5, 0f)
                if (rng.nextFloat() < 0.5f) powerup(16f)
                if (rng.nextBoolean()) obs((l + 1 + rng.nextInt(2)) % L, 0, 4f)
                26f }
            4 -> { var open = rng.nextInt(L)
                val gap = 16f - 5f * diff
                for (k in 0..2) {
                    for (l in 0 until L) if (l != open) obs(l, 0, k * gap)
                    open = (open + if (rng.nextBoolean()) 1 else L - 1) % L
                }
                gap * 2f + 10f }
            5 -> { for (l in 0 until L) obs(l, 2, 0f)
                val ys = floatArrayOf(1.8f, 3.0f, 3.2f, 3.0f, 1.8f)
                for (k in 0 until L) orb(laneX(k), ys[k], 4f + k * 2.2f)
                18f }
            6 -> { val l = rng.nextInt(L)
                obs(l, 3, 0f)
                if (l > 0) obs(l - 1, 0, 0f)
                if (l < L - 1) obs(l + 1, 0, 0f)
                orbLine(l, 1.0f, 3, 4f)
                18f }
            7 -> { obs(rng.nextInt(L), 0, 0f, dr)
                obs(rng.nextInt(L), 1, 10f)
                powerup(15f)
                24f }
            8 -> { for (l in 0 until L) chasm(l, 0f)
                orbArc(rng.nextInt(L), -6f)
                16f }
            9 -> { var cl = rng.nextInt(L)
                var safe = (cl + 2) % L
                for (k in 0..3) {
                    chasm(cl, k * 9f, 4.5f)
                    orb(laneX(safe), 1.0f, k * 9f + 1f)
                    orb(laneX(safe), 1.0f, k * 9f + 4f)
                    cl = (cl + if (rng.nextBoolean()) 1 else L - 1).coerceIn(0, L - 1)
                    safe = ((safe + if (rng.nextBoolean()) 1 else L - 1) + L) % L
                    if (safe == cl) safe = (safe + 1) % L
                }
                42f }
            10 -> { for (k in 0..2) {
                    for (l in 0 until L) hang(l, k * 8f)
                    orbLine(rng.nextInt(L), 1.0f, 2, k * 8f + 2.5f)
                }
                32f }
            11 -> { for (k in 0..3) for (l in 0 until L) obs(l, 1, k * 7f)
                orbLine(rng.nextInt(L), 2.2f, 4, 0f)
                28f }
            12 -> { val mid = (0 until L).filter { it in 1 until L - 1 }
                for (k in 0..1) for (l in mid) obs(l, 0, k * 13f, 1.3f)
                orbLine(0, 1.0f, 3, 2f)
                orbLine(L - 1, 1.0f, 3, 2f)
                26f }
            13 -> { var open = rng.nextInt(L)
                val gap = 12f - 4f * diff
                for (k in 0..4) {
                    for (l in 0 until L) if (l != open) obs(l, 0, k * gap)
                    if (k % 2 == 0) orb(laneX(open), 1.0f, k * gap + 3f)
                    open = (open + if (rng.nextBoolean()) 1 else L - 1) % L
                }
                gap * 4f + 10f }
            14 -> { val c = 1 + rng.nextInt(L - 2)
                for (l in 0 until L) if (l != c) obs(l, 0, 0f)
                orb(laneX(c), 1.6f, 0f, if (rng.nextBoolean()) 1 else 2)
                obs(c, 1, 7f)
                16f }
            else -> { for (l in 0 until L) obs(l, 1, 0f)
                val gap = rng.nextInt(L)
                for (l in 0 until L) if (l != gap) obs(l, 0, 9f)
                val c1 = rng.nextInt(L - 1)
                chasm(c1, 18f, 5f); chasm(c1 + 1, 18f, 5f)
                orbArc(gap, 11f)
                26f }
        }
    }
}

object Input {
    const val LEFT = 0
    const val RIGHT = 1
    const val JUMP = 2
    const val DIVE = 3
    const val RESTART = 4
    const val BRANCH_0 = 5
    const val BRANCH_1 = 6
    const val BRANCH_2 = 7
    const val REVIVE = 8
}
