package com.github.mytv.data.restricted

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ParentPinTest {
    private class MapPinStore : PinStore {
        val values = LinkedHashMap<String, String>()

        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private fun pin(store: MapPinStore = MapPinStore(), start: Long = 0L): ParentPin =
        ParentPin(store = store, clock = Clock { start })

    @Test
    fun storesSaltedHashAndNoPlaintext() {
        val store = MapPinStore()
        val pin = pin(store)
        assertTrue(pin.set("1234"))
        assertTrue(pin.hasPin())

        assertFalse(store.values.values.any { it.contains("1234") })
        val decodedSizes = store.values.values.mapNotNull { value ->
            runCatching { Base64.getDecoder().decode(value) }.getOrNull()
        }.map { it.size }.toSet()
        assertTrue(decodedSizes.contains(16))
        assertTrue(decodedSizes.contains(32))

        val firstSalt = store.values["pin_salt"]
        assertTrue(pin.set("1234"))
        assertNotEquals(firstSalt, store.values["pin_salt"])

        val reopened = ParentPin(store)
        assertTrue(reopened.verify("1234", 1_000L) is PinVerifyResult.Success)
    }

    @Test
    fun acceptsDpadPin() {
        val pin = pin()
        assertTrue(pin.set("UP,UP,DOWN,DOWN"))
        assertTrue(pin.verify("UUDD", 1_000L) is PinVerifyResult.Success)
        assertTrue(pin.verify("up up down down", 2_000L) is PinVerifyResult.Success)
        assertTrue(pin.verify("UP,UP,DOWN,LEFT", 3_000L) is PinVerifyResult.Wrong)
        assertFalse(pin.set("12AB"))
        assertFalse(pin.set("12345"))
    }

    @Test
    fun fiveWrongEntriesLockForSixtySeconds() {
        val pin = pin()
        pin.set("1234")
        val t0 = 1_000_000L
        repeat(4) { index ->
            val result = pin.verify("0000", t0 + index)
            assertTrue(result is PinVerifyResult.Wrong)
            assertEquals(4 - index, (result as PinVerifyResult.Wrong).attemptsLeft)
        }
        val locked = pin.verify("0000", t0 + 10L)
        assertTrue(locked is PinVerifyResult.Locked)
        assertEquals(t0 + 10L + 60_000L, (locked as PinVerifyResult.Locked).lockedUntil)

        assertTrue(pin.verify("1234", t0 + 30_000L) is PinVerifyResult.Locked)
        assertTrue(pin.verify("1234", t0 + 70_000L) is PinVerifyResult.Success)
    }

    @Test
    fun correctPinUnlocksForTenMinutes() {
        val pin = pin()
        pin.set("1234")
        val t0 = 5_000_000L
        val result = pin.verify("1234", t0)
        assertTrue(result is PinVerifyResult.Success)
        assertEquals(t0 + 10 * 60 * 1000L, (result as PinVerifyResult.Success).unlockedUntil)
        assertTrue(pin.isUnlocked(t0 + 599_999L))
        assertFalse(pin.isUnlocked(t0 + 600_000L))
    }

    @Test
    fun homeDreamAndScreenOffRelock() {
        val pin = pin()
        pin.set("1234")
        val t0 = 7_000_000L

        pin.verify("1234", t0)
        assertTrue(pin.isUnlocked(t0 + 1L))
        pin.onHome(t0 + 2L)
        assertFalse(pin.isUnlocked(t0 + 3L))

        pin.verify("1234", t0 + 4L)
        pin.onDream(t0 + 5L)
        assertFalse(pin.isUnlocked(t0 + 6L))

        pin.verify("1234", t0 + 7L)
        pin.onScreenOff(t0 + 8L)
        assertFalse(pin.isUnlocked(t0 + 9L))
    }

    @Test
    fun pausedModeResumesOnDreamScreenOffAndReboot() {
        val pin = pin()
        pin.set("1234")
        pin.pauseMode("kids")
        pin.onHome(100L)
        assertEquals("kids", pin.pausedMode())

        assertEquals("kids", pin.onDream(200L))
        assertNull(pin.pausedMode())

        pin.pauseMode("kids")
        assertEquals("kids", pin.onScreenOff(300L))
        assertNull(pin.pausedMode())

        pin.pauseMode("elders")
        assertEquals("elders", pin.restoreAfterReboot(400L))
        assertNull(pin.pausedMode())
    }

    @Test
    fun clearRemovesPinAndPausedState() {
        val pin = pin()
        pin.set("UP,UP,DOWN,DOWN")
        pin.pauseMode("kids")
        pin.clear()
        assertFalse(pin.hasPin())
        assertTrue(pin.verify("1234", 0L) is PinVerifyResult.NoPin)
        assertNull(pin.pausedMode())
        assertFalse(pin.lockState(0L).hasPin)
    }
}
