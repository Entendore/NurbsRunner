package com.example.nurbstrunner

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class GameRenderer(
    private val world: GameWorld,
    private val settings: Settings,
    private val onHud: (CharSequence, CharSequence, UiState) -> Unit
) : GLSurfaceView.Renderer {

    private companion object {
        const val TRACK_STEP = 4f
        const val TRACK_FRONT = 16f
        const val TRACK_HALF = 4.4f       // main road half-width
        const val FORK_HALF = 2.45f       // branch half-width at full divergence
        const val SKIRT = 7f
        const val VPR = 12                // verts per row: 3 strips × 4
        const val CULL_Z = -178f          // fog far = 170; cull just past it
    }

    private lateinit var prop: Program
    private lateinit var body: Program
    private lateinit var trackProg: Program
    private lateinit var sky: Program

    private val meshPlayer = GpuMesh()
    private val meshGhost = GpuMesh()
    private val meshCube = GpuMesh()
    private val meshOrb = GpuMesh()
    private val meshGate = GpuMesh()
    private val meshDisc = GpuMesh()
    private val meshTrack = GpuMesh()

    private val mModel = FloatArray(16)
    private val mView = FloatArray(16)
    private val mProj = FloatArray(16)
    private val mVP = FloatArray(16)
    private val mMVP = FloatArray(16)

    private val eye = floatArrayOf(0f, 3.3f, 8.2f)
    private var aspect = 1f
    private val fogNow = FloatArray(3)
    private val hor = FloatArray(3)
    private val zen = FloatArray(3)
    private val tmpCol = FloatArray(3)
    private val gateCol = FloatArray(3)
    private val accCol = FloatArray(3)

    private val colTall = floatArrayOf(0.88f, 0.26f, 0.30f)
    private val colLow = floatArrayOf(0.95f, 0.62f, 0.16f)
    private val colOrb = floatArrayOf(1.00f, 0.84f, 0.25f)
    private val colJump = floatArrayOf(0.25f, 1.00f, 0.50f)
    private val colBoost = floatArrayOf(0.25f, 0.85f, 1.00f)
    private val colMagnet = floatArrayOf(0.72f, 0.45f, 1.00f)
    private val colOver = floatArrayOf(1.00f, 0.42f, 0.20f)
    private val colShieldUp = floatArrayOf(0.30f, 1.00f, 0.70f)
    private val colVoid = floatArrayOf(0.06f, 0.015f, 0.10f)
    private val colHang = floatArrayOf(0.35f, 0.40f, 0.55f)
    private val WHITE = floatArrayOf(1f, 1f, 1f)
    private val BLACK = floatArrayOf(0f, 0f, 0f)

    private var b0f = 0f
    private var h0f = 0f
    private var offX = 0f
    private var offY = 0f

    private var trackRows = 64
    private lateinit var trackVerts: FloatArray
    private val rnd = kotlin.random.Random(7)

    // cached per-frame / on-change appearance (avoids per-frame allocations)
    private var frameApp: Genetics.Appearance = Genetics.appearance(world.genes)
    private var ghostApp: Genetics.Appearance? = null
    private val gColA = FloatArray(3)
    private val gColB = FloatArray(3)

    private var fovSmooth = 64f
    private var lastFrame = 0L
    private var time = 0f
    private var hudTimer = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prop = Shaders.run { Program(buildSafe(VERTEX, FRAGMENT, FALLBACK_FRAGMENT, "prop")) }
        body = Shaders.run { Program(buildSafe(BODY_VERTEX, BODY_FRAGMENT, FALLBACK_FRAGMENT, "body")) }
        trackProg = Shaders.run { Program(buildSafe(TRACK_VERTEX, TRACK_FRAGMENT, FALLBACK_TRACK_FRAGMENT, "track")) }
        sky = Shaders.run { Program(buildSafe(VERTEX, SKY_FRAGMENT, FALLBACK_FRAGMENT, "sky")) }

        meshCube.upload(Meshes.cube())
        meshOrb.upload(Meshes.sphere())
        meshGate.upload(Meshes.torus(4.3f, 0.16f))
        meshDisc.upload(Meshes.disc())

        // quality preset applies here — also the point where EGL context loss
        // recovers, since everything above is rebuilt from scratch
        trackRows = when (settings.quality) { 0 -> 40; 1 -> 64; else -> 84 }
        trackVerts = FloatArray(trackRows * VPR * 8)
        meshTrack.upload(buildTrackMesh())
        rebuildPlayerMesh()
        rebuildGhost()
        frameApp = Genetics.appearance(world.genes)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        glCheck("onSurfaceCreated")
        lastFrame = SystemClock.uptimeMillis()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.uptimeMillis()
        var dt = (now - lastFrame) / 1000f
        lastFrame = now
        if (dt > 0.05f) dt = 0.05f
        if (dt <= 0f) dt = 0.016f
        time += dt

        world.update(dt)
        // skip CPU NURBS rebuilds while the body is collapsed to a point
        if (world.meshDirty && world.fx.bodyScale() > 0.05f) {
            rebuildPlayerMesh()
            frameApp = Genetics.appearance(world.genes)
        } else if (world.fx.active) {
            frameApp = Genetics.appearance(world.genes)   // colors morph during fx
        }
        if (world.ghostDirty) rebuildGhost()

        // zone palette -> fog / sky (allocation-free)
        Themes.sampleInto(world.scroll, 5, fogNow)
        Themes.sampleInto(world.scroll, 2, accCol)
        for (i in 0..2) {
            hor[i] = (fogNow[i] * 1.9f).coerceAtMost(1f)
            zen[i] = fogNow[i] * 0.32f
        }
        GLES30.glClearColor(fogNow[0], fogNow[1], fogNow[2], 1f)

        // camera: shake + speed FOV + fork zoom-out + death-cam + banking
        val shake = world.shake
        val sx = if (shake > 0f) (rnd.nextFloat() - 0.5f) * shake * 0.5f else 0f
        val sy = if (shake > 0f) (rnd.nextFloat() - 0.5f) * shake * 0.4f else 0f
        val bAhead = world.bendAt(world.scroll + 45f) - world.bendAt(world.scroll)
        val hAhead = world.heightAt(world.scroll + 28f) - world.heightAt(world.scroll)
        val px = world.playerX
        eye[0] += (px * 0.45f + bAhead * 0.30f - eye[0]) * min(1f, 4f * dt)
        val roll = ((world.bendAt(world.scroll + 12f) - world.bendAt(world.scroll - 6f)) / 18f) * -26f
        val envAhead = world.envAt(world.scroll + 55f)
        var targetFov = 64f + min(12f, max(0f, world.speed - 20f) * 0.5f)
                + (if (world.overT > 0f) 8f else 0f)
                + GameConfig.FORK_FOV_EXTRA * envAhead      // zoom-out: see both roads
                - world.fx.fovPinch()                       // cinematic pinch at BURST
        if (world.gameOver) targetFov = 55f                 // death-cam pull-in
        fovSmooth += (targetFov - fovSmooth) * min(1f, dt * 6f)
        Matrix.perspectiveM(mProj, 0, fovSmooth, aspect, 0.1f, 800f)
        Matrix.setLookAtM(mView, 0,
            eye[0] + sx, 3.3f + world.jumpY * 0.35f + sy, eye[2],
            px * 0.8f + bAhead * 1.1f, 1.1f + world.jumpY * 0.5f + hAhead * 0.9f, -6f,
            0f, 1f, 0f)
        Matrix.rotateM(mView, 0, roll.coerceIn(-8f, 8f), 0f, 0f, 1f)
        Matrix.multiplyMM(mVP, 0, mProj, 0, mView, 0)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        drawSky()
        fillTrackVerts()
        meshTrack.updateVerts(trackVerts)
        drawTrack()
        drawShadow()

        prop.use()
        GLES30.glUniform3f(prop.loc("uLightDir"), 0.45f, 0.85f, 0.55f)
        GLES30.glUniform3f(prop.loc("uEye"), eye[0], eye[1], eye[2])
        GLES30.glUniform3fv(prop.loc("uFogColor"), 1, fogNow, 0)
        GLES30.glUniform1f(prop.loc("uFogNear"), 45f)
        GLES30.glUniform1f(prop.loc("uFogFar"), 170f)
        GLES30.glUniform1f(prop.loc("uTime"), time)
        GLES30.glUniform1f(prop.loc("uFade"), 0f)

        drawDecor()
        drawGates()
        drawObstacles()
        drawOrbs()
        drawPlayer()
        drawGhost()
        drawEvolutionFx()
        drawBeams()
        drawTrail()

        hudTimer -= dt
        if (hudTimer <= 0f) { hudTimer = 0.12f; postHud() }
    }

    private fun glCheck(where: String) {
        if (!Logx.ENABLED) return
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) Logx.e("GL", "$where: 0x${err.toString(16)}")
    }

    private fun rebuildPlayerMesh() {
        meshPlayer.upload(Nurbs.buildBodyMesh(world.genes))
        world.meshDirty = false
    }

    private fun rebuildGhost() {
        val g = world.ghostGenes
        if (g == null) { ghostApp = null; world.ghostDirty = false; return }
        meshGhost.upload(Nurbs.buildBodyMesh(g))
        ghostApp = Genetics.appearance(g)
        world.ghostDirty = false
    }

    // ---------------- sky ----------------

    private fun drawSky() {
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        Matrix.setIdentityM(mModel, 0)
        Matrix.translateM(mModel, 0, eye[0], eye[1], eye[2])
        Matrix.scaleM(mModel, 0, 600f, 600f, 600f)
        Matrix.multiplyMM(mMVP, 0, mVP, 0, mModel, 0)
        sky.use()
        GLES30.glUniformMatrix4fv(sky.loc("uMVP"), 1, false, mMVP, 0)
        GLES30.glUniformMatrix4fv(sky.loc("uModel"), 1, false, mModel, 0)
        GLES30.glUniform3f(sky.loc("uEye"), eye[0], eye[1], eye[2])
        GLES30.glUniform3fv(sky.loc("uHorizon"), 1, hor, 0)
        GLES30.glUniform3fv(sky.loc("uZenith"), 1, zen, 0)
        GLES30.glUniform3fv(sky.loc("uAccent"), 1, accCol, 0)
        GLES30.glUniform1f(sky.loc("uTime"), time)
        meshOrb.draw()
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
    }

    // ---------------- track: 3 strips per row (left branch / right branch / void) ----

    private fun buildTrackMesh(): MeshData {
        val idx = ShortArray((trackRows - 1) * 3 * 18)
        var ip = 0
        for (r in 0 until trackRows - 1) {
            for (s in 0..2) {
                val b = (r * VPR + s * 4).toShort()
                val c = (r * VPR + s * 4 + 4).toShort()
                idx[ip++] = b; idx[ip++] = (b + 1).toShort(); idx[ip++] = c
                idx[ip++] = (b + 1).toShort(); idx[ip++] = (c + 1).toShort(); idx[ip++] = c
                idx[ip++] = b; idx[ip++] = c; idx[ip++] = (b + 2).toShort()
                idx[ip++] = (b + 2).toShort(); idx[ip++] = c; idx[ip++] = (c + 2).toShort()
                idx[ip++] = (b + 1).toShort(); idx[ip++] = (b + 3).toShort(); idx[ip++] = (c + 1).toShort()
                idx[ip++] = (b + 3).toShort(); idx[ip++] = (c + 3).toShort(); idx[ip++] = (c + 1).toShort()
            }
        }
        return MeshData(trackVerts, idx)
    }

    /** Writes 4 verts (topL, topR, skirtL, skirtR). dv = signed distance for uv.y. */
    private fun strip(vi: Int, cx: Float, half: Float, y: Float, z: Float,
                      ny: Float, nz: Float, dv: Float, void: Boolean): Int {
        var p = vi
        fun put(x: Float, yy: Float, u: Float) {
            trackVerts[p++] = x; trackVerts[p++] = yy; trackVerts[p++] = z
            trackVerts[p++] = 0f; trackVerts[p++] = ny; trackVerts[p++] = nz
            trackVerts[p++] = u; trackVerts[p++] = dv
        }
        val uL = if (void) 8.9f else -1f
        val uR = if (void) 10.9f else 1f
        put(cx - half, y, uL)
        put(cx + half, y, uR)
        if (void) { put(cx - half, y, uL); put(cx + half, y, uR) }   // degenerate skirts
        else { put(cx - half, y - SKIRT, -1.6f); put(cx + half, y - SKIRT, 1.6f) }
        return p
    }

    private fun degen(vi: Int, ox: Float, oy: Float, z: Float): Int {
        var p = vi
        repeat(4) {
            trackVerts[p++] = ox; trackVerts[p++] = oy; trackVerts[p++] = z
            trackVerts[p++] = 0f; trackVerts[p++] = 1f; trackVerts[p++] = 0f
            trackVerts[p++] = 0f; trackVerts[p++] = 0f
        }
        return p
    }

    private fun fillTrackVerts() {
        b0f = world.bendAt(world.scroll)
        h0f = world.heightAt(world.scroll)
        var vi = 0
        for (r in 0 until trackRows) {
            val z = TRACK_FRONT - r * TRACK_STEP
            val d = world.scroll - z
            val ox = world.bendAt(d) - b0f
            val oy = world.heightAt(d) - h0f
            val dh = (world.heightAt(d + 2f) - world.heightAt(d - 2f)) * 0.25f
            val inv = 1f / sqrt(1f + dh * dh)
            val ny = inv; val nz = dh * inv

            val env = world.envAt(d)
            if (env < 0.003f) {
                vi = strip(vi, ox, TRACK_HALF, oy, z, ny, nz, d, void = false)
                vi = degen(vi, ox, oy, z)
                vi = degen(vi, ox, oy, z)
            } else {
                val spread = GameConfig.FORK_SPREAD * env
                val half = TRACK_HALF + (FORK_HALF - TRACK_HALF) * env
                vi = strip(vi, ox - spread, half, oy + 0.02f, z, ny, nz, -d, void = false)
                vi = strip(vi, ox + spread, half, oy + 0.04f, z, ny, nz, -d, void = false)
                val vHalf = (5.55f * env - TRACK_HALF).coerceAtLeast(0f)
                vi = if (vHalf > 0.05f) strip(vi, ox, vHalf, oy, z, ny, nz, d, void = true)
                     else degen(vi, ox, oy, z)
            }
        }
    }

    private fun drawTrack() {
        trackProg.use()
        GLES30.glUniformMatrix4fv(trackProg.loc("uVP"), 1, false, mVP, 0)
        GLES30.glUniform3f(trackProg.loc("uEye"), eye[0], eye[1], eye[2])
        GLES30.glUniform3fv(trackProg.loc("uFogColor"), 1, fogNow, 0)
        GLES30.glUniform1f(trackProg.loc("uFogNear"), 45f)
        GLES30.glUniform1f(trackProg.loc("uFogFar"), 170f)
        GLES30.glUniform1f(trackProg.loc("uTime"), time)
        meshTrack.draw()
    }

    // ---------------- props ----------------

    private fun drawProp(mesh: GpuMesh, color: FloatArray, emissive: Float,
                         rim: Float, alpha: Float = 1f, fogFar: Float = 170f, fade: Float = 0f) {
        Matrix.multiplyMM(mMVP, 0, mVP, 0, mModel, 0)
        GLES30.glUniformMatrix4fv(prop.loc("uMVP"), 1, false, mMVP, 0)
        GLES30.glUniformMatrix4fv(prop.loc("uModel"), 1, false, mModel, 0)
        GLES30.glUniform3fv(prop.loc("uColor"), 1, color, 0)
        GLES30.glUniform1f(prop.loc("uEmissive"), emissive)
        GLES30.glUniform1f(prop.loc("uRim"), rim)
        GLES30.glUniform1f(prop.loc("uAlpha"), alpha)
        GLES30.glUniform1f(prop.loc("uFogFar"), fogFar)
        GLES30.glUniform1f(prop.loc("uFade"), fade)
        mesh.draw()
    }

    /** Soft blob shadow under the player — key jump-readability aid. */
    private fun drawShadow() {
        val fx = world.fx
        val sc = fx.bodyScale()
        if (sc <= 0.05f) return
        val alpha = 0.55f * (1f - min(1f, world.jumpY / 5f))
        if (alpha <= 0.02f) return
        val r = world.sizeFactor * sc * (1.05f + world.jumpY * 0.18f)
        Matrix.setIdentityM(mModel, 0)
        Matrix.translateM(mModel, 0, world.playerX, h0f + 0.06f, 0f)
        Matrix.scaleM(mModel, 0, r, 1f, r)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        drawProp(meshDisc, BLACK, 0f, 0f, alpha = alpha, fogFar = 9999f, fade = 1f)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun computeOff(z: Float) {
        val d = world.scroll - z
        offX = world.bendAt(d) - b0f
        offY = world.heightAt(d) - h0f
    }

    private fun drawDecor() {
        for (c in world.decor) {
            val z = world.scroll - c.d
            if (z < CULL_Z) continue
            computeOff(z)
            Themes.sampleInto(c.d, if (c.kind % 2 == 0) 2 else 3, tmpCol)
            val flick = 0.45f + 0.30f * sin(time * 4f + c.d * 0.7f)
            when (c.kind) {
                0 -> {
                    Matrix.setIdentityM(mModel, 0)
                    Matrix.translateM(mModel, 0, c.x + offX, offY + c.s * 0.5f, z)
                    Matrix.scaleM(mModel, 0, 0.5f, c.s, 0.5f)
                    drawProp(meshCube, tmpCol, flick, 0.2f)
                }
                1 -> {
                    Matrix.setIdentityM(mModel, 0)
                    Matrix.translateM(mModel, 0, offX, offY + 0.1f, z)
                    drawProp(meshGate, tmpCol, 0.7f, 0.4f)
                }
                2 -> {
                    Matrix.setIdentityM(mModel, 0)
                    Matrix.translateM(mModel, 0, c.x + offX, offY + 1.0f + c.s * 0.6f, z)
                    Matrix.rotateM(mModel, 0, time * 25f + c.d, 0f, 1f, 0f)
                    Matrix.rotateM(mModel, 0, 45f, 0f, 0f, 1f)
                    Matrix.scaleM(mModel, 0, c.s * 0.5f, c.s * 1.1f, c.s * 0.5f)
                    drawProp(meshCube, tmpCol, 0.6f, 0.5f)
                }
                4 -> {
                    Matrix.setIdentityM(mModel, 0)
                    Matrix.translateM(mModel, 0, c.x + offX, offY + c.s * 0.5f, z)
                    Matrix.scaleM(mModel, 0, 0.65f, c.s, 0.65f)
                    drawProp(meshCube, tmpCol, 0.25f + 0.15f * sin(time * 1.7f + c.d), 0.25f)
                }
                5 -> {
                    for (k in 0..2) {
                        Matrix.setIdentityM(mModel, 0)
                        Matrix.translateM(mModel, 0, c.x + offX + k * 0.9f - 0.9f,
                            offY + 0.6f + k * 0.9f, z)
                        Matrix.rotateM(mModel, 0, time * 12f + k * 2f, 0f, 1f, 0f)
                        Matrix.scaleM(mModel, 0, 0.5f, 0.9f + k * 0.4f, 0.5f)
                        drawProp(meshCube, tmpCol, 0.5f, 0.4f)
                    }
                }
                else -> {
                    Matrix.setIdentityM(mModel, 0)
                    Matrix.translateM(mModel, 0, c.x + offX, offY + 2.4f + sin(time + c.d) * 0.3f, z)
                    Matrix.rotateM(mModel, 0, time * 40f + c.d, 0f, 0f, 1f)
                    Matrix.scaleM(mModel, 0, c.s, c.s, c.s)
                    drawProp(meshGate, tmpCol, 0.8f, 0.3f)
                }
            }
        }
    }

    private fun drawGates() {
        val app = frameApp
        for (i in 0..2) gateCol[i] = (app.colA[i] + app.colB[i]) * 0.5f
        for (g in world.gates) {
            if (g.z < CULL_Z) continue
            computeOff(g.z)
            Matrix.setIdentityM(mModel, 0)
            Matrix.translateM(mModel, 0, offX, 4.35f + offY, g.z)
            drawProp(meshGate, gateCol, 0.7f + 0.4f * sin(time * 6f), 0.5f)
        }
    }

    private fun drawBeams() {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        for (g in world.gates) {
            if (g.z < CULL_Z) continue
            computeOff(g.z)
            for (side in intArrayOf(-1, 1)) {
                Matrix.setIdentityM(mModel, 0)
                Matrix.translateM(mModel, 0, side * 3.1f + offX, 4.5f + offY, g.z)
                Matrix.scaleM(mModel, 0, 0.12f, 9f, 0.12f)
                drawProp(meshCube, WHITE, 1.2f, 0f, alpha = 0.5f, fogFar = 9999f)
            }
        }
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawObstacles() {
        for (o in world.obstacles) {
            if (o.z < CULL_Z) continue
            computeOff(o.z)
            val bx = world.sideOffAt(world.scroll - o.z, o.side)
            val lx = o.xAt(world.t) + bx + offX
            val driftPulse = if (o.drift != 0f) 0.25f + 0.25f * sin(time * 7f) else 0f
            when (o.kind) {
                0 -> {
                    Matrix.setIdentityM(mModel, 0)
                    Matrix.translateM(mModel, 0, lx, 1.3f + offY, o.z)
                    Matrix.scaleM(mModel, 0, 1.6f, 2.6f, 1.2f)
                    drawProp(meshCube, colTall, 0.08f + driftPulse, 0.15f)
                }
                1 -> {
                    Matrix.setIdentityM(mModel, 0)
                    Matrix.translateM(mModel, 0, lx, 0.35f + offY, o.z)
                    Matrix.scaleM(mModel, 0, 1.6f, 0.7f, 1.2f)
                    drawProp(meshCube, colLow, 0.14f, 0.2f)
                }
                2 -> {
                    Matrix.setIdentityM(mModel, 0)
                    Matrix.translateM(mModel, 0, lx, 0.07f + offY, o.z)
                    Matrix.scaleM(mModel, 0, 1.7f, 0.14f, 1.7f)
                    drawProp(meshCube, colJump, 0.95f, 0.3f)
                }
                3 -> {
                    Matrix.setIdentityM(mModel, 0)
                    Matrix.translateM(mModel, 0, lx, 0.07f + offY, o.z)
                    Matrix.scaleM(mModel, 0, 1.7f, 0.14f, 1.7f)
                    drawProp(meshCube, colBoost, 0.95f, 0.3f)
                }
                4 -> {
                    Matrix.setIdentityM(mModel, 0)
                    Matrix.translateM(mModel, 0, lx, 0.04f + offY, o.z)
                    Matrix.scaleM(mModel, 0, 1.55f, 0.08f, o.len)
                    val ember = 0.9f + 0.4f * sin(time * 6f + o.z * 0.7f)
                    drawProp(meshCube, colVoid, ember, 0.4f)
                }
                else -> {
                    val hb = world.hangBottom()
                    Matrix.setIdentityM(mModel, 0)
                    Matrix.translateM(mModel, 0, lx, hb + 0.95f + offY, o.z)
                    Matrix.scaleM(mModel, 0, 1.5f, 1.9f, 1.0f)
                    drawProp(meshCube, colHang, 0.10f, 0.2f)
                }
            }
        }
    }

    private fun drawOrbs() {
        for (o in world.orbs) {
            if (o.taken || o.z < CULL_Z) continue
            computeOff(o.z)
            val bx = world.sideOffAt(world.scroll - o.z, o.side)
            val col = when (o.kind) {
                1 -> colMagnet; 2 -> colOver; 3 -> colShieldUp; else -> colOrb
            }
            val pulse = if (o.kind != 0) 1.1f + 0.25f * sin(time * 8f) else 1f
            Matrix.setIdentityM(mModel, 0)
            Matrix.translateM(mModel, 0, o.x + bx + offX,
                o.y + offY + sin(time * 3f + o.x * 2f) * 0.1f, o.z)
            Matrix.scaleM(mModel, 0, 0.32f * pulse, 0.32f * pulse, 0.32f * pulse)
            drawProp(meshOrb, col, if (o.kind != 0) 1.1f else 0.85f, 0.4f)
        }
    }

    private fun drawPlayer() {
        val fx = world.fx
        val sc = fx.bodyScale()
        if (sc <= 0.02f) return                       // collapsed at BURST
        val app = frameApp
        val s = world.sizeFactor
        val bob = if (world.grounded && !fx.active) sin(time * 2.6f) * 0.05f else 0f
        var cx = world.playerX
        var cy = 0.85f * s + world.jumpY + bob
        if (fx.phase == EvolutionFx.Phase.CHARGE) {   // charge tremor
            val tr = fx.pt * 0.04f
            cx += sin(time * 57f) * tr
            cy += sin(time * 73f) * tr
        }
        val bank = ((world.targetPlayerX() - world.playerX) * -0.55f).coerceIn(-0.9f, 0.9f)

        Matrix.setIdentityM(mModel, 0)
        Matrix.translateM(mModel, 0, cx, cy, 0f)
        Matrix.rotateM(mModel, 0, time * 40f + fx.spinExtra() + bank * 57.3f, 0f, 1f, 0f)
        Matrix.rotateM(mModel, 0, bank * 25f, 0f, 0f, 1f)
        Matrix.scaleM(mModel, 0, sc, sc, sc)
        // squash & stretch on hard landings / hits (v7 game feel)
        val sq = world.squash
        if (sq > 0.01f) {
            Matrix.scaleM(mModel, 0, 1f + 0.28f * sq, 1f - 0.42f * sq, 1f + 0.28f * sq)
        }
        Matrix.multiplyMM(mMVP, 0, mVP, 0, mModel, 0)

        body.use()
        GLES30.glUniformMatrix4fv(body.loc("uMVP"), 1, false, mMVP, 0)
        GLES30.glUniformMatrix4fv(body.loc("uModel"), 1, false, mModel, 0)
        GLES30.glUniform1f(body.loc("uTime"), time)
        GLES30.glUniform1f(body.loc("uAmp"), app.amp)
        GLES30.glUniform1f(body.loc("uWFreq"), app.wfreq)
        GLES30.glUniform1f(body.loc("uWSpeed"), app.wspeed)
        GLES30.glUniform3fv(body.loc("uColA"), 1, app.colA, 0)
        GLES30.glUniform3fv(body.loc("uColB"), 1, app.colB, 0)
        GLES30.glUniform1f(body.loc("uPattern"), app.pattern.toFloat())
        GLES30.glUniform1f(body.loc("uPatFreq"), app.patFreq)
        GLES30.glUniform1f(body.loc("uPatCon"), app.patCon)
        GLES30.glUniform1f(body.loc("uEmissive"), app.emissive + fx.glowBoost() + world.flash * 0.6f)
        GLES30.glUniform1f(body.loc("uPulse"), app.pulse)
        GLES30.glUniform1f(body.loc("uSparkle"), app.sparkle)
        GLES30.glUniform1f(body.loc("uRim"), app.rim + fx.rimBoost())
        GLES30.glUniform1f(body.loc("uFlash"), world.flash * 0.5f)
        GLES30.glUniform1f(body.loc("uAlpha"), 1f)
        GLES30.glUniform3f(body.loc("uLightDir"), 0.45f, 0.85f, 0.55f)
        GLES30.glUniform3f(body.loc("uEye"), eye[0], eye[1], eye[2])
        GLES30.glUniform3fv(body.loc("uFogColor"), 1, fogNow, 0)
        GLES30.glUniform1f(body.loc("uFogNear"), 45f)
        GLES30.glUniform1f(body.loc("uFogFar"), 170f)
        meshPlayer.draw()
    }

    private fun desat(src: FloatArray, dst: FloatArray) {
        val l = (src[0] + src[1] + src[2]) / 3f
        for (i in 0..2) dst[i] = src[i] * 0.35f + l * 0.45f
    }

    /** The Ancestor Ghost: the parent form, running translucently alongside you. */
    private fun drawGhost() {
        val app = ghostApp ?: return
        val s = world.sizeFactor * 0.92f
        val bob = sin(time * 1.7f) * 0.07f

        Matrix.setIdentityM(mModel, 0)
        Matrix.translateM(mModel, 0, world.ghostLaneX(), 0.85f * s + bob, -2.6f)
        Matrix.rotateM(mModel, 0, time * 25f, 0f, 1f, 0f)
        Matrix.multiplyMM(mMVP, 0, mVP, 0, mModel, 0)

        desat(app.colA, gColA); desat(app.colB, gColB)
        body.use()
        GLES30.glUniformMatrix4fv(body.loc("uMVP"), 1, false, mMVP, 0)
        GLES30.glUniformMatrix4fv(body.loc("uModel"), 1, false, mModel, 0)
        GLES30.glUniform1f(body.loc("uTime"), time)
        GLES30.glUniform1f(body.loc("uAmp"), app.amp * 0.5f)
        GLES30.glUniform1f(body.loc("uWFreq"), app.wfreq)
        GLES30.glUniform1f(body.loc("uWSpeed"), app.wspeed * 0.7f)
        GLES30.glUniform3fv(body.loc("uColA"), 1, gColA, 0)
        GLES30.glUniform3fv(body.loc("uColB"), 1, gColB, 0)
        GLES30.glUniform1f(body.loc("uPattern"), app.pattern.toFloat())
        GLES30.glUniform1f(body.loc("uPatFreq"), app.patFreq)
        GLES30.glUniform1f(body.loc("uPatCon"), app.patCon * 0.5f)
        GLES30.glUniform1f(body.loc("uEmissive"), app.emissive * 0.4f)
        GLES30.glUniform1f(body.loc("uPulse"), app.pulse)
        GLES30.glUniform1f(body.loc("uSparkle"), app.sparkle * 0.2f)
        GLES30.glUniform1f(body.loc("uRim"), app.rim * 0.6f)
        GLES30.glUniform1f(body.loc("uFlash"), 0f)
        GLES30.glUniform1f(body.loc("uAlpha"), 0.35f)
        GLES30.glUniform3f(body.loc("uLightDir"), 0.45f, 0.85f, 0.55f)
        GLES30.glUniform3f(body.loc("uEye"), eye[0], eye[1], eye[2])
        GLES30.glUniform3fv(body.loc("uFogColor"), 1, fogNow, 0)
        GLES30.glUniform1f(body.loc("uFogNear"), 45f)
        GLES30.glUniform1f(body.loc("uFogFar"), 170f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)   // additive spirit glow
        GLES30.glDepthMask(false)
        meshGhost.draw()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** BURST/GROW shockwave ring + light pillar, in the new trail color. */
    private fun drawEvolutionFx() {
        val fx = world.fx
        val k = fx.ringK()
        if (k < 0f) return
        val app = frameApp
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        val fade = 1f - k
        val s = (0.55f + k * 4.6f) / 4.3f
        Matrix.setIdentityM(mModel, 0)
        Matrix.translateM(mModel, 0, world.playerX, 0.25f + h0f, 0f)
        Matrix.rotateM(mModel, 0, 90f, 1f, 0f, 0f)
        Matrix.scaleM(mModel, 0, s, s, s)
        drawProp(meshGate, app.trail, 1.4f, 0f, alpha = fade * fade * 0.85f, fogFar = 9999f)

        val pk = fx.pillarK()
        if (pk > 0f) {
            val h = 5f * pk + 0.3f
            Matrix.setIdentityM(mModel, 0)
            Matrix.translateM(mModel, 0, world.playerX, h / 2f + h0f, 0f)
            Matrix.scaleM(mModel, 0, 0.10f + 0.30f * (1f - pk), h, 0.10f + 0.30f * (1f - pk))
            drawProp(meshCube, WHITE, 1.6f, 0f, alpha = 0.55f * pk, fogFar = 9999f)
        }

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawTrail() {
        if (world.trail.isEmpty()) return
        val app = frameApp
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        for (p in world.trail) {
            val lf = (p.life / p.maxLife).coerceIn(0f, 1f)
            computeOff(p.z)
            val bx = world.sideOffAt(world.scroll - p.z, p.side)
            Matrix.setIdentityM(mModel, 0)
            Matrix.translateM(mModel, 0, p.x + bx + offX, p.y + offY, p.z)
            val sc = p.size * (0.35f + 0.65f * lf)
            Matrix.scaleM(mModel, 0, sc, sc, sc * p.stretch)
            drawProp(meshOrb, app.trail, 1.2f * lf, 0f, alpha = lf * 0.9f, fogFar = 9999f)
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    // ---------------- HUD ----------------

    private fun rgb(c: FloatArray): Int {
        val r = (c[0].coerceIn(0f, 1f) * 255f).roundToInt()
        val g = (c[1].coerceIn(0f, 1f) * 255f).roundToInt()
        val b = (c[2].coerceIn(0f, 1f) * 255f).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun SpannableStringBuilder.cs(text: String, color: Int) {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun postHud() {
        val app = frameApp
        val b = SpannableStringBuilder()
        val info = 0xFF9FE8FF.toInt()
        val gold = 0xFFFFD479.toInt()
        val dim = 0xFFB0B8C8.toInt()
        val green = 0xFF7CFF6B.toInt()
        val ln = world.lineage

        b.cs("SCORE ${world.score.toInt()}  ${world.distance.toInt()}m  ", info)
        b.cs("BEST ${ln.bestDist.toInt()}m", gold)
        b.cs("  SPD %.0f  ".format(world.speed), info)
        b.cs("❤".repeat(world.shield.coerceAtLeast(0)), 0xFFFF5A6E.toInt())
        b.append('\n')

        val fill = (world.evoCharge * 10f).toInt()
        b.cs("EVO ", info)
        b.cs("█".repeat(fill), green)
        b.cs("░".repeat(10 - fill), 0xFF3A4A5A.toInt())
        if (world.styleCombo >= 2) b.cs("  STYLE ×${world.styleMult}", gold)
        else if (world.combo >= 2) b.cs("  x${min(5, 1 + world.combo / 5)} COMBO", gold)
        if (world.magnetT > 0f) b.cs("  MAG %.0fs".format(world.magnetT), 0xFFC084FF.toInt())
        if (world.overT > 0f) b.cs("  OVR %.0fs".format(world.overT), 0xFFFF7043.toInt())
        b.append('\n')

        // live evolution phase readout
        if (world.fx.active) {
            val nm = when (world.fx.phase) {
                EvolutionFx.Phase.CHARGE -> "CHARGING"
                EvolutionFx.Phase.BURST -> "BURST"
                EvolutionFx.Phase.GROW -> "REFORMING"
                else -> "STABILIZING"
            }
            val f = (world.fx.pt * 8f).toInt()
            b.cs("EVOLVING $nm ", green)
            b.cs("▓".repeat(f) + "░".repeat(8 - f), green)
            b.append('\n')
        }

        b.cs("GEN %d  FIT %.2f (%+.2f)  SP %d  AGE %d".format(
            world.generation, world.lastFitness, world.lastDelta,
            world.lastSpecies, world.lineage.evolutions), gold)
        b.append('\n')

        // heritable trait chromosome (color = strength)
        b.cs("TRAIT ", dim)
        for (i in 0 until Traits.N) {
            val v = world.traits[i]
            val col = when {
                v > 0.66f -> 0xFF7CFF6B.toInt()
                v > 0.33f -> 0xFFFFD479.toInt()
                else -> 0xFF8892A0.toInt()
            }
            b.cs("${Traits.NAMES[i]}%02d ".format((v * 99).toInt()), col)
        }
        b.append('\n')

        // zone name in its own accent color + fork preview + bend warning
        b.cs("ZONE ${Themes.NAMES[Themes.zoneIndex(world.scroll + 60f)]}",
            rgb(Themes.sample(world.scroll, 2)))
        if (world.roadSide != 0) {
            b.cs("  ROAD ${world.roadLabel()}", 0xFFFF9A3D.toInt())
        }
        world.forkPreview()?.let { b.cs("  ⚑ $it", 0xFFFF9A3D.toInt()) }
        val bd = world.bendAt(world.scroll + 80f) - world.bendAt(world.scroll)
        val n = (abs(bd) * 1.2f).toInt().coerceIn(0, 3)
        if (n > 0) {
            val arrow = if (bd > 0) "▶" else "◀"
            b.cs("  BEND ${arrow.repeat(n)}", 0xFFFF9A3D.toInt())
        }
        b.append('\n')

        b.cs("SKIN ${ln.describe(world.genes)}  ", dim)
        b.cs("● ", rgb(app.colA))
        b.cs("● ", rgb(app.colB))
        b.cs("●", rgb(app.trail))
        b.append('\n')

        b.cs("FIT HIST " + ln.history.joinToString(" ") { "%.1f".format(it) }, dim)

        // immutable snapshot for the UI thread (thread-safety contract)
        val ui = UiState(
            gameOver = world.gameOver,
            branchActive = world.branchChoiceActive,
            branchOptions = world.branches?.map {
                BranchOption(it.label, it.skin, Traits.describe(it.traits))
            } ?: emptyList(),
            laneMoved = world.lane != (GameConfig.LANES - 1) / 2,
            distanceM = world.distance.toInt(),
            saveJson = world.takeSaveJson(),
            reviveOffer = world.reviveOffer && world.branchChoiceActive
        )
        onHud(b, if (world.announceT > 0f) world.announceMsg else "", ui)
    }
}
