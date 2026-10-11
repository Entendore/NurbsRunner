package com.example.nurbstrunner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

private class QuietSfx : GameSfx {
    override fun play(id: Int) {}
    override fun buzz(kind: Int) {}
}

class GeneticsTest {
    @Test fun `evolution never decreases fitness`() {
        repeat(20) {
            val seed = Genetics.randomGenome()
            val (_, fit, sp) = Genetics.evolve(seed, 0f, 6, 16)
            assertTrue(fit >= Genetics.fitness(seed, 0f) - 1e-3f)
            assertTrue(sp >= 1)
        }
    }
    @Test fun `mutate stays finite and bounded via sizeFactor`() {
        val g = Genetics.randomGenome()
        repeat(500) { Genetics.mutate(g) }
        assertTrue(Genetics.sizeFactor(g) in 0.4f..2.0f)
        assertTrue(Genetics.fitness(g, 0f).isFinite())
    }
    @Test fun `appearance decodes pattern and frequency`() {
        val a = Genetics.appearance(Genetics.randomGenome())
        assertTrue(a.pattern in 0..3)
        assertTrue(a.patFreq >= 2f)
        for (c in listOf(a.colA, a.colB, a.trail)) c.forEach { assertTrue(it in 0f..1f) }
    }
}

class NurbsTest {
    @Test fun `mesh indices and counts are valid`() {
        val m = Nurbs.buildBodyMesh(Genetics.randomGenome())
        assertEquals(36 * 25 * 6, m.idx.size)               // cols=36, rows=26
        val nVerts = m.verts.size / 8
        assertEquals(36 * 26, nVerts)
        for (i in m.idx) assertTrue(i.toInt() in 0 until nVerts)
    }
    @Test fun `normals are normalized`() {
        val m = Nurbs.buildBodyMesh(Genetics.randomGenome())
        var i = 3
        while (i < m.verts.size) {
            val n = sqrt(m.verts[i] * m.verts[i] + m.verts[i + 1] * m.verts[i + 1] +
                    m.verts[i + 2] * m.verts[i + 2])
            assertTrue(abs(n - 1f) < 1e-3f)
            i += 8
        }
    }
}

class TrackPathTest {
    @Test fun `field is continuous and bounded`() {
        val p = TrackPath(kotlin.random.Random(42), 55f, 5.5f, 9f, 0.2f)
        var d = 0f
        while (d < 4000f) {
            assertTrue(abs(p.at(d)) < 14f)                  // maxAbs + Catmull overshoot
            assertTrue(abs(p.at(d + 0.5f) - p.at(d)) < 1.5f)
            d += 0.5f
        }
    }
}

class ThemesTest {
    @Test fun `palette slots stay in range and zones cycle`() {
        val out = FloatArray(3)
        var d = 0f
        while (d <= 2000f) {
            for (s in 0..5) {
                Themes.sampleInto(d, s, out)
                out.forEach { assertTrue(it in 0f..1f) }
            }
            d += 7.3f
        }
        assertEquals(0, Themes.zoneIndex(0f))
        assertEquals(1, Themes.zoneIndex(Themes.ZONE_LEN))
        assertEquals(0, Themes.zoneIndex(Themes.ZONE_LEN * Themes.PAL.size))
    }
}

class TraitsTest {
    @Test fun `traits stay bounded under heavy pressure`() {
        val t = Traits.defaults()
        val p = FloatArray(Traits.N) { 1f }
        repeat(200) { Traits.evolve(t, p) }
        t.forEach { assertTrue(it in 0f..1f) }
    }
    @Test fun `armor threshold flips at 0_5`() {
        val t = Traits.defaults()
        assertEquals(1, Traits.hitsPerShield(t))
        t[Traits.P_HIDE] = 0.75f
        assertEquals(2, Traits.hitsPerShield(t))
    }
    @Test fun `pressure raises the matching trait`() {
        val t = Traits.defaults()
        val before = t[Traits.P_HUNGER]
        val p = FloatArray(Traits.N)
        p[Traits.P_HUNGER] = 1f
        repeat(20) { Traits.evolve(t, p) }
        assertTrue(t[Traits.P_HUNGER] > before)
    }
    @Test fun `branch label reflects dominant trait`() {
        val t = Traits.defaults()
        t[Traits.P_HIDE] = 0.9f
        assertEquals("ARMORED", Traits.branchLabel(t))
        assertEquals("BALANCED", Traits.branchLabel(Traits.defaults()))
    }
}

class ForkSpanTest {
    private val s = ForkSpan(100f, 200f, 2, "ORBS", "GAUNTLET")

    @Test fun `env is zero outside and one mid-span`() {
        assertEquals(0f, s.envAt(99f), 1e-5f)
        assertEquals(1f, s.envAt(150f), 1e-5f)
        assertEquals(0f, s.envAt(201f), 1e-5f)
    }
    @Test fun `env transitions are symmetric`() {
        assertTrue(abs(s.envAt(110f) - s.envAt(190f)) < 1e-5f)
        assertTrue(s.envAt(110f) in 0.001f..0.999f)
    }
}

class RunLogTest {
    @Test fun `run log caps at the configured maximum`() {
        val l = Lineage()
        repeat(20) { l.recordRun(it * 10, it, it) }
        assertEquals(GameConfig.RUN_HISTORY_MAX, l.runLog.size)
        assertEquals(190, l.runLog.last().dist)
    }
}

class AnnouncePriorityTest {
    @Test fun `lower priority cannot interrupt higher`() {
        val w = GameWorld(Settings(), Lineage(), QuietSfx())
        w.announce("HIGH", 2f, 3)
        w.announce("LOW", 2f, 1)
        assertEquals("HIGH", w.announceMsg)
        // expire via real sim steps (~2.5 s of sim time across frames)
        repeat(50) { w.update(0.05f) }
        w.announce("LOW", 2f, 1)
        assertEquals("LOW", w.announceMsg)
    }
}

class StyleSystemTest {
    @Test fun `style multiplier caps at five`() {
        val w = GameWorld(Settings(), Lineage(), QuietSfx())
        repeat(30) { w.styleAward(1f) }
        assertEquals(GameConfig.STYLE_MAX_MULT, w.styleMult)
        // mult progression 1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5x15 -> total 114
        assertEquals(114f, w.score, 0.01f)
    }
    @Test fun `cost table covers every pattern`() {
        assertEquals(16, PATTERN_COST.size)
        PATTERN_COST.forEach { assertTrue(it in 0f..1f) }
    }
}
