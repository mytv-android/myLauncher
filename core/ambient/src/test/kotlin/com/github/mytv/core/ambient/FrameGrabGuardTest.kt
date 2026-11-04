package com.github.mytv.core.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGrabGuardTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun successfulGrabOver150msDisablesPixelCopy() {
        val guard = FrameGrabGuard("fingerprint-a")
        guard.onSuccessfulGrab(151)
        assertFalse(guard.canUsePixelCopy)
        assertTrue(guard.currentVerdict().pixelCopyDisabled)
    }

    @Test
    fun successfulGrabAt150msIsStillAllowed() {
        val guard = FrameGrabGuard("fingerprint-b")
        guard.onSuccessfulGrab(150)
        assertTrue(guard.canUsePixelCopy)
    }

    @Test
    fun sixteenByNineGridTreatsMaxLuminanceBelowFourPercentAsBlack() {
        val dark = IntArray(16 * 9) { 0xFF0A0A0A.toInt() }
        val brighter = IntArray(16 * 9) { 0xFF0C0C0C.toInt() }
        assertTrue(FrameLuminance.isAllBlack(dark, 16, 9))
        assertFalse(FrameLuminance.isAllBlack(brighter, 16, 9))
        assertTrue(FrameLuminance.isAllBlack(IntArray(16 * 9) { black }, 16, 9))
        val oneWhite = IntArray(16 * 9) { black }
        oneWhite[4 * 16 + 7] = white
        assertFalse(FrameLuminance.isAllBlack(oneWhite, 16, 9))
    }

    @Test
    fun twoBlackGrabsWithNonNearBlackKeyColorDisablePixelCopy() {
        val guard = FrameGrabGuard("fingerprint-c")
        guard.onFrameAnalyzed(white, allBlack = true)
        assertTrue(guard.canUsePixelCopy)
        guard.onFrameAnalyzed(white, allBlack = true)
        assertFalse(guard.canUsePixelCopy)
    }

    @Test
    fun nearBlackKeyColorDoesNotDisablePixelCopy() {
        val guard = FrameGrabGuard("fingerprint-d")
        guard.onFrameAnalyzed(black, allBlack = true)
        guard.onFrameAnalyzed(black, allBlack = true)
        assertTrue(guard.canUsePixelCopy)
        assertTrue(FrameGrabGuard.isNearBlack(black))
        assertFalse(FrameGrabGuard.isNearBlack(white))
    }

    @Test
    fun verdictIsStoredUnderFingerprint() {
        val store = MemoryFrameGrabVerdictStore()
        val first = FrameGrabGuard("firmware-1", store)
        first.onSuccessfulGrab(9_000)
        assertFalse(first.canUsePixelCopy)
        assertFalse(FrameGrabGuard("firmware-1", store).canUsePixelCopy)
        assertTrue(FrameGrabGuard("firmware-2", store).canUsePixelCopy)
    }

    @Test
    fun metadataRetrieverTimeoutDisablesFileGrabsForThisBoot() {
        val boot = FileGrabBootState()
        val guard = FrameGrabGuard("fingerprint-e", bootState = boot)
        assertTrue(guard.canGrabFromFile)
        guard.onFileGrabTimeout()
        assertFalse(guard.canGrabFromFile)
        assertFalse(FrameGrabGuard("fingerprint-f", bootState = boot).canGrabFromFile)
    }

    @Test
    fun devicePresetWinsOverMeasuredAndStoredVerdicts() {
        val store = MemoryFrameGrabVerdictStore()
        store.write(
            "fingerprint-g",
            FrameGrabVerdict(pixelCopyDisabled = true, fileGrabDisabled = true),
        )
        val allowed = FrameGrabGuard(
            "fingerprint-g",
            store,
            preset = FrameGrabPreset(pixelCopyDisabled = false, fileGrabDisabled = false),
        )
        assertTrue(allowed.canUsePixelCopy)
        assertTrue(allowed.canGrabFromFile)

        val disabled = FrameGrabGuard(
            "fingerprint-h",
            preset = FrameGrabPreset(pixelCopyDisabled = true, fileGrabDisabled = true),
        )
        disabled.onSuccessfulGrab(1)
        assertFalse(disabled.canUsePixelCopy)
        assertFalse(disabled.canGrabFromFile)
    }

    @Test
    fun nonBlackFrameResetsTheConsecutiveBlackCounter() {
        val guard = FrameGrabGuard("fingerprint-i")
        guard.onFrameAnalyzed(white, allBlack = true)
        guard.onFrameAnalyzed(white, allBlack = false)
        guard.onFrameAnalyzed(white, allBlack = true)
        assertTrue(guard.canUsePixelCopy)
        assertEquals(1, guard.currentVerdict().consecutiveBlackGrabs)
    }
}
