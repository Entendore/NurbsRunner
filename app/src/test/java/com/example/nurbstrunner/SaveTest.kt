package com.example.nurbstrunner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SnapshotRoundTripTest {
    @Test fun `snapshot restores lineage and settings exactly`() {
        val s1 = Settings().apply { quality = 1; sound = false; music = false }
        val l1 = Lineage().apply {
            best = Genetics.randomGenome(); bestFit = 4.2f; bestDist = 1234f
            evolutions = 7; deaths = 2
            traits = floatArrayOf(0.9f, 0.1f, 0.5f, 0.3f, 0.7f, 0.2f)
            repeat(3) { recordAncestor(Genetics.randomGenome(), it, 1f + it, 0f) }
            recordDeath("WALL", 500, 3, "SKIN")
            recordRun(500, 900, 3); recordRun(700, 1200, 4)
        }
        val json = SaveStore.snapshot(s1, l1)

        val s2 = Settings(); val l2 = Lineage()
        assertTrue(SaveStore.restore(json, s2, l2))
        assertEquals(7, l2.evolutions); assertEquals(2, l2.deaths)
        assertEquals(1234f, l2.bestDist); assertEquals(4.2f, l2.bestFit)
        assertTrue(l2.best!!.contentEquals(l1.best!!))
        assertTrue(l2.traits.contentEquals(l1.traits))
        assertEquals(3, l2.ancestors.size); assertEquals(1, l2.deathLog.size)
        assertEquals(2, l2.runLog.size)
        assertEquals(1, s2.quality); assertEquals(false, s2.sound); assertEquals(false, s2.music)
    }
    @Test fun `restore rejects garbage and future versions`() {
        assertTrue(!SaveStore.restore("not json", Settings(), Lineage()))
        assertTrue(!SaveStore.restore("""{"v":99}""", Settings(), Lineage()))
    }
}

class CrashGuardTest {
    @Test fun `writes, caps, and clears local logs`() {
        val dir = Files.createTempDirectory("cg").toFile()
        val g = CrashGuard(dir, maxFiles = 2)
        g.install()
        repeat(4) { g.uncaughtException(Thread("t$it"), RuntimeException("boom$it")) }
        val logs = dir.listFiles { f -> f.name.startsWith("crash-") }!!
        assertEquals(2, logs.size)
        assertTrue(logs.any { it.readText().contains("boom") })
        g.clear()
        assertEquals(0, dir.listFiles { f -> f.name.startsWith("crash-") }!!.size)
    }
}
