package com.github.mytv.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    private val presets = """
        {
          "profiles": [
            {
              "manufacturer": "NVIDIA",
              "model": "SHIELD Android TV",
              "conclusions": {
                "pixelCopy": "ok",
                "bootDelayMs": "1500"
              }
            },
            {
              "manufacturer": "Xiaomi",
              "model": "Mi TV 4",
              "conclusions": {
                "decoderDelayAfterInputMs": "2500"
              }
            }
          ]
        }
    """.trimIndent()

    private fun info(
        manufacturer: String,
        model: String,
        device: String = "device",
        fingerprint: String = "brand/device/device:1/build:user/test-keys",
    ): DeviceInfo = DeviceInfo(manufacturer, model, device, fingerprint)

    @Test
    fun matchesManufacturerAndModelCaseInsensitively() {
        val profile = DeviceProfile.fromJson(
            presets,
            info = info("  nvidia ", "shield android tv"),
        )
        assertTrue(profile.matched)
        assertEquals(0, profile.matchedIndex)
        assertEquals("ok", profile.conclusion("pixelCopy"))
        assertEquals("1500", profile.conclusion("bootDelayMs"))

        val noMatch = DeviceProfile.fromJson(
            presets,
            info = info("NVIDIA", "SHIELD"),
        )
        assertFalse(noMatch.matched)
        assertNull(noMatch.matchedIndex)
        assertNull(noMatch.conclusion("pixelCopy"))
    }

    @Test
    fun deviceAndFingerprintPrefixNarrowTheMatch() {
        val json = """
            {
              "profiles": [
                {
                  "manufacturer": "Xiaomi",
                  "model": "Mi TV 4",
                  "device": "machuca",
                  "fingerprintPrefix": "Xiaomi/machuca",
                  "conclusions": {"decoderDelayAfterInputMs": "2500"}
                },
                {
                  "manufacturer": "Xiaomi",
                  "model": "Mi TV 4",
                  "conclusions": {"bootDelayMs": "800"}
                }
              ]
            }
        """.trimIndent()

        val specific = DeviceProfile.fromJson(
            json,
            info = info(
                manufacturer = "xiaomi",
                model = "mi tv 4",
                device = "MACHUCA",
                fingerprint = "Xiaomi/machuca/machuca:9/build:user/test-keys",
            ),
        )
        assertEquals(0, specific.matchedIndex)
        assertEquals("2500", specific.conclusion("decoderDelayAfterInputMs"))
        assertNull(specific.conclusion("bootDelayMs"))

        val generic = DeviceProfile.fromJson(
            json,
            info = info(
                manufacturer = "Xiaomi",
                model = "Mi TV 4",
                device = "other",
                fingerprint = "Xiaomi/other/other:9/build:user/test-keys",
            ),
        )
        assertEquals(1, generic.matchedIndex)
        assertEquals("800", generic.conclusion("bootDelayMs"))
    }

    @Test
    fun adbOverridesTakePriorityAndEmptyValueClears() {
        val withOverrides = DeviceProfile.fromJson(
            presets,
            overrides = mapOf("pixelCopy" to "black", "bootDelayMs" to "2500"),
            info = info("NVIDIA", "SHIELD Android TV"),
        )
        assertEquals("black", withOverrides.conclusion("pixelCopy"))
        assertEquals("2500", withOverrides.conclusion("bootDelayMs"))

        val cleared = DeviceProfile.fromJson(
            presets,
            overrides = mapOf("pixelCopy" to ""),
            info = info("NVIDIA", "SHIELD Android TV"),
        )
        assertNull(cleared.conclusion("pixelCopy"))
        assertEquals("1500", cleared.conclusion("bootDelayMs"))
        assertNull(cleared.conclusion("unknownKey"))
        assertFalse(cleared.conclusions().containsKey("pixelCopy"))
    }

    @Test
    fun parsesAdbOverridePayload() {
        val overrides = DeviceProfile.parseOverrides(
            """{"pixelCopy":"ok","bootDelayMs":2500,"noThirdPartyDream":true,"cleared":null}""",
        )
        assertEquals("ok", overrides["pixelCopy"])
        assertEquals("2500", overrides["bootDelayMs"])
        assertEquals("true", overrides["noThirdPartyDream"])
        assertEquals("", overrides["cleared"])
        assertTrue(overrides.containsKey("cleared"))

        assertTrue(DeviceProfile.parseOverrides("").isEmpty())
        assertTrue(DeviceProfile.parseOverrides("{not json}").isEmpty())
    }

    @Test
    fun malformedAndIncompleteProfilesAreIgnored() {
        val json = """
            {
              "profiles": [
                {"manufacturer": "", "model": "Mi TV 4", "conclusions": {"a": "b"}},
                {"model": "Mi TV 4", "conclusions": {"a": "b"}},
                {"manufacturer": "Xiaomi", "conclusions": {"a": "b"}}
              ]
            }
        """.trimIndent()
        val profile = DeviceProfile.fromJson(json, info = info("Xiaomi", "Mi TV 4"))
        assertFalse(profile.matched)

        val malformed = DeviceProfile.fromJson(
            "{ this is not json",
            overrides = mapOf("pixelCopy" to "ok"),
            info = info("Xiaomi", "Mi TV 4"),
        )
        assertFalse(malformed.matched)
        assertEquals("ok", malformed.conclusion("pixelCopy"))
    }
}
