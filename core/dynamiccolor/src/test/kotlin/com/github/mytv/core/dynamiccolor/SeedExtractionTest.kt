package com.github.mytv.core.dynamiccolor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedExtractionTest {
    private class FakeCache : SeedCache {
        val values = HashMap<String, Int>()
        val puts = ArrayList<Triple<String, Int, Int>>()
        var gets = 0

        override suspend fun get(key: String, algorithmVersion: Int): Int? {
            gets++
            return values[key + "#" + algorithmVersion]
        }

        override suspend fun put(key: String, algorithmVersion: Int, result: Int) {
            puts += Triple(key, algorithmVersion, result)
            values[key + "#" + algorithmVersion] = result
        }
    }

    private fun pixels(size: Int = 48, color: (Int) -> Int): IntArray =
        IntArray(size * size) { color(it) }

    @Test
    fun fixedIconSeedSnapshot() {
        val sample = pixels { index ->
            when {
                index % 10 < 7 -> 0xFF1D9EA8.toInt()
                index % 10 < 9 -> 0xFF2BB3BD.toInt()
                else -> 0xFF0F7F88.toInt()
            }
        }
        val seed = SeedExtractor.extract(sample)
        assertTrue("expected a seed, got $seed", seed is SeedResult.Seed)
        assertEquals("#2BB3BD", ColorMath.hex((seed as SeedResult.Seed).argb))
    }

    @Test
    fun fixedPosterSeedSnapshot() {
        val sample = pixels { index ->
            val x = index % 48
            val y = index / 48
            if (x + y < 48) 0xFFE48140.toInt() else 0xFF481665.toInt()
        }
        val seed = SeedExtractor.extract(sample)
        assertTrue("expected a seed, got $seed", seed is SeedResult.Seed)
        assertEquals("#E48140", ColorMath.hex((seed as SeedResult.Seed).argb))
    }

    @Test
    fun lowChromaSourceIsRejected() {
        val gray = pixels { index ->
            when {
                index % 3 == 0 -> 0xFF767676.toInt()
                index % 3 == 1 -> 0xFF8A8A8A.toInt()
                else -> 0xFF5E5E5E.toInt()
            }
        }
        assertEquals(SeedResult.LowChroma, SeedExtractor.extract(gray))
    }

    @Test
    fun transparentPixelsAreIgnored() {
        val sample = pixels { index ->
            if (index % 2 == 0) {
                0x00FF0000
            } else {
                0xFF808080.toInt()
            }
        }
        assertEquals(SeedResult.LowChroma, SeedExtractor.extract(sample))

        val colorful = pixels { index ->
            if (index % 2 == 0) {
                0xF9FF0000.toInt()
            } else {
                0xFF1D9EA8.toInt()
            }
        }
        val seed = SeedExtractor.extract(colorful)
        assertTrue(seed is SeedResult.Seed)
        assertTrue(ColorMath.deltaE((seed as SeedResult.Seed).argb, 0xFF1D9EA8.toInt()) < 30f)
    }

    @Test
    fun algorithmVersionIsPartOfCacheKey() = runBlocking {
        val cache = FakeCache()
        val extractor = SeedExtractor(cache)
        val key = SourceKey("app:com.example/0/0", 48, 48)
        var loads = 0
        val result = extractor.seedFor(key) {
            loads++
            pixels { 0xFF1D9EA8.toInt() }
        }
        assertTrue(result is SeedResult.Seed)
        assertEquals(1, loads)
        assertEquals(1, cache.puts.size)
        val (cacheKey, version, _) = cache.puts.first()
        assertEquals(SEED_ALGORITHM_VERSION, version)
        assertTrue(cacheKey.contains("app:com.example/0/0"))
        assertTrue(cacheKey.contains("48x48"))

        val again = extractor.seedFor(key) {
            loads++
            null
        }
        assertTrue(again is SeedResult.Seed)
        assertEquals(1, loads)
        assertFalse(cache.puts.size > 1)
    }
}
