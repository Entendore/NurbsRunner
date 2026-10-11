package com.example.nurbstrunner

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import kotlin.math.sin
import kotlin.random.Random

/**
 * All sound effects are synthesized to 16-bit mono WAVs on first launch and
 * played through SoundPool. Zero bundled resources; files live in private
 * internal storage (fully local). Haptics use VibrationEffect with legacy
 * fallback.
 */
class SfxManager(context: Context, private val settings: Settings) : GameSfx {

    private companion object {
        const val SR = 22050
        const val DIR = "sfx_v3"      // bump to invalidate cached WAVs
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()

    private val loaded = IntArray(Sfx.COUNT)
    private val rng = Random(1234)

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val dir = File(context.filesDir, DIR).apply { mkdirs() }

    init {
        val gens = arrayOf(
            blend(sweep(0.10f, 900f, 1400f), 0.55f),                        // ORB
            blend(sweep(0.22f, 500f, 1000f), 0.50f),                        // POWER
            blend(noise(0.16f, 0.40f), 0.85f),                              // HIT
            blend(sweep(0.12f, 280f, 620f), 0.55f),                         // JUMP
            mix(blend(sweep(0.28f, 200f, 900f, square = true), 0.35f),      // BOOST
                blend(noise(0.28f), 0.15f)),
            blend(seg(sweep(0.16f, 660f, 660f), sweep(0.16f, 880f, 880f),   // EVOLVE
                sweep(0.26f, 1320f, 1320f)), 0.55f),
            mix(blend(sweep(0.60f, 440f, 90f), 0.65f),                      // DEATH
                blend(noise(0.30f), 0.20f)),
            blend(sweep(0.04f, 1200f, 900f), 0.35f),                        // UI
            blend(whoosh(0.10f, 0.25f, 0.55f), 0.45f),                      // SWOOSH
            blend(whoosh(0.18f, 0.12f, 0.65f), 0.55f),                      // WHOOSH
            mix(blend(noise(0.22f, 0.55f), 0.80f),                          // SMASH
                blend(sweep(0.22f, 160f, 55f), 0.80f)),
            mix(blend(sweep(0.09f, 150f, 70f), 0.60f),                      // LAND
                blend(noise(0.06f, 0.30f), 0.25f)),
            seg(blend(sweep(0.07f, 880f, 880f), 0.50f), silence(0.07f),     // WARN
                blend(sweep(0.07f, 880f, 880f), 0.50f)),
            mix(blend(sweep(0.72f, 220f, 880f), 0.50f),                     // CHARGE
                blend(sweep(0.72f, 330f, 1320f), 0.22f)),
            mix(blend(noise(0.35f, 0.60f), 0.75f),                          // BURST
                blend(sweep(0.40f, 90f, 38f), 0.85f)),
            seg(blend(sweep(0.06f, 700f, 900f), 0.40f),                     // GROW
                blend(sweep(0.06f, 900f, 1150f), 0.40f),
                blend(sweep(0.09f, 1150f, 1500f), 0.42f)),
            mix(blend(sweep(0.55f, 1318f, 1318f), 0.40f),                   // SETTLE
                blend(sweep(0.55f, 1975f, 1975f), 0.28f)),
            seg(blend(sweep(0.05f, 1046f, 1046f), 0.50f),                   // PERFECT
                blend(sweep(0.10f, 1568f, 1568f), 0.55f))
        )
        for (id in gens.indices) {
            val f = File(dir, "s$id.wav")
            if (!f.exists() || f.length() == 0L) writeWav(f, gens[id])
            loaded[id] = try { pool.load(f.absolutePath, 1) } catch (t: Throwable) { 0 }
        }
    }

    override fun play(id: Int) {
        if (!settings.sound) return
        val sid = loaded.getOrNull(id) ?: return
        if (sid != 0) pool.play(sid, 0.9f, 0.9f, 1, 0, 0.92f + rng.nextFloat() * 0.16f)
    }

    override fun buzz(kind: Int) {
        if (!settings.haptics) return
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                when (kind) {
                    Buzz.LIGHT -> v.vibrate(VibrationEffect.createOneShot(18, 90))
                    Buzz.MEDIUM -> v.vibrate(VibrationEffect.createOneShot(42, 160))
                    else -> v.vibrate(VibrationEffect.createWaveform(
                        longArrayOf(0, 60, 60, 90), intArrayOf(0, 200, 0, 255), -1))
                }
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(if (kind == Buzz.HEAVY) 120L else if (kind == Buzz.MEDIUM) 42L else 18L)
            }
        } catch (t: Throwable) { Logx.e("Sfx", "vibrate failed: ${t.message}") }
    }

    fun release() { runCatching { pool.release() } }

    // ---------------- tiny synth ----------------

    private fun env(i: Int, n: Int): Float {
        val x = i.toFloat() / n
        val atk = (x / 0.02f).coerceAtMost(1f)
        val rel = ((1f - x) / 0.55f).coerceAtMost(1f)
        return atk * rel * rel
    }

    private fun sweep(dur: Float, f0: Float, f1: Float, square: Boolean = false): FloatArray {
        val n = (SR * dur).toInt()
        val out = FloatArray(n)
        var ph = 0f
        for (i in 0 until n) {
            val f = f0 + (f1 - f0) * (i.toFloat() / n)
            ph += 6.28318f * f / SR
            val s = if (square) (2f * ((ph / 6.28318f) % 1f) - 1f) else sin(ph)
            out[i] = s * env(i, n)
        }
        return out
    }

    private fun noise(dur: Float, lp: Float = 0.25f): FloatArray {
        val n = (SR * dur).toInt()
        val out = FloatArray(n)
        var y = 0f
        for (i in 0 until n) {
            y += lp * ((rng.nextFloat() * 2f - 1f) - y)
            out[i] = y * 2.2f * env(i, n)
        }
        return out
    }

    /** Airy filtered-noise swell with a moving low-pass — swooshes/whooshes. */
    private fun whoosh(dur: Float, lp0: Float, lp1: Float): FloatArray {
        val n = (SR * dur).toInt()
        val out = FloatArray(n)
        var y = 0f
        for (i in 0 until n) {
            val lp = lp0 + (lp1 - lp0) * (i.toFloat() / n)
            y += lp * ((rng.nextFloat() * 2f - 1f) - y)
            val x = i.toFloat() / n
            val atk = (x / 0.25f).coerceAtMost(1f)
            val rel = ((1f - x) / 0.6f).coerceAtMost(1f)
            out[i] = y * 2.4f * atk * rel
        }
        return out
    }

    private fun seg(vararg parts: FloatArray): FloatArray {
        val n = parts.sumOf { it.size }
        val out = FloatArray(n)
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    private fun silence(dur: Float): FloatArray = FloatArray((SR * dur).toInt())

    /** Element-wise sum of overlapping layers (writeWav clamps the result). */
    private fun mix(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.maxOf { it.size })
        for (p in parts) for (i in p.indices) out[i] += p[i]
        return out
    }

    private fun blend(a: FloatArray, gain: Float): FloatArray =
        FloatArray(a.size) { (a[it] * gain).coerceIn(-1f, 1f) }

    private fun writeWav(f: File, pcm: FloatArray) {
        val n = pcm.size
        val data = ByteArray(44 + n * 2)
        fun le(off: Int, v: Int) {
            data[off] = v.toByte(); data[off + 1] = (v shr 8).toByte()
            data[off + 2] = (v shr 16).toByte(); data[off + 3] = (v shr 24).toByte()
        }
        "RIFF".toByteArray().copyInto(data, 0)
        le(4, 36 + n * 2)
        "WAVE".toByteArray().copyInto(data, 8)
        "fmt ".toByteArray().copyInto(data, 12)
        le(16, 16); data[20] = 1; data[22] = 1
        le(24, SR); le(28, SR * 2); data[32] = 2; data[34] = 16
        "data".toByteArray().copyInto(data, 36)
        le(40, n * 2)
        for (i in 0 until n) {
            val s = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt()
            data[44 + i * 2] = s.toByte(); data[45 + i * 2] = (s shr 8).toByte()
        }
        f.writeBytes(data)
    }
}
