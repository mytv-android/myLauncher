package com.github.mytv.data.media

import com.github.mytv.core.ambient.ScreensaverHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientResumeTest {
    private class MemoryStore : AmbientResumeStore {
        var state = AmbientResumeState()
        override fun read(): AmbientResumeState = state
        override fun write(state: AmbientResumeState) {
            this.state = state
        }
    }

    private var now: Long = 1_700_000_000_000L

    @Test
    fun positionIsSavedEveryTenSeconds() {
        val store = MemoryStore()
        val resume = AmbientResume(store) { now }
        assertTrue(resume.onPosition("clip-a", 1_000))
        now += 5_000
        assertFalse(resume.onPosition("clip-a", 6_000))
        now += 5_000
        assertTrue(resume.onPosition("clip-a", 11_000))
        assertEquals("clip-a", store.state.point?.clipId)
        assertEquals(11_000L, store.state.point?.positionMs)
    }

    @Test
    fun leavingAmbientAlwaysSavesThePosition() {
        val store = MemoryStore()
        val resume = AmbientResume(store) { now }
        resume.onPosition("clip-a", 1_000)
        now += 1_000
        resume.saveOnLeave("clip-a", 2_000)
        assertEquals(2_000L, store.state.point?.positionMs)
    }

    @Test
    fun resumePointSurvivesARestart() {
        val store = MemoryStore()
        AmbientResume(store) { now }.saveOnLeave("clip-b", 42_000)
        val restored = AmbientResume(store) { now }.resumePoint()
        assertNotNull(restored)
        assertEquals("clip-b", restored?.clipId)
        assertEquals(42_000L, restored?.positionMs)
    }

    @Test
    fun rotationHoldsThreeClipsAndChangesOnePerDay() {
        val store = MemoryStore()
        val resume = AmbientResume(store) { now }
        val all = listOf("a", "b", "c", "d", "e")
        val first = resume.rotationFor(all, day = "20260101")
        assertEquals(listOf("a", "b", "c"), first)
        assertEquals(first, resume.rotationFor(all, day = "20260101"))

        val second = resume.rotationFor(all, day = "20260102")
        assertEquals(3, second.size)
        assertFalse("a" in second)
        assertTrue("b" in second && "c" in second)
        assertTrue(second.last() in listOf("d", "e"))
    }

    @Test
    fun screensaverFromAmbientReusesTheSamePlayer() {
        val resume = AmbientResume(MemoryStore()) { now }
        assertEquals(
            ScreensaverHandoff.REUSE_SAME_PLAYER,
            resume.screensaverHandoff(fromAmbient = true, qualityChanged = false),
        )
        assertEquals(
            ScreensaverHandoff.RESTART,
            resume.screensaverHandoff(fromAmbient = true, qualityChanged = true),
        )
        assertEquals(
            ScreensaverHandoff.RESTART,
            resume.screensaverHandoff(fromAmbient = false, qualityChanged = false),
        )
    }
}
