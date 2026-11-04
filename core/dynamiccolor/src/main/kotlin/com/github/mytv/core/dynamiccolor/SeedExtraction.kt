package com.github.mytv.core.dynamiccolor

import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface SeedResult {
    data class Seed(val argb: Int) : SeedResult

    data object LowChroma : SeedResult

    data object Unavailable : SeedResult
}

data class SourceKey(
    val value: String,
    val sampleWidth: Int = 48,
    val sampleHeight: Int = 48,
)

class SeedExtractor(
    private val cache: SeedCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun seedFor(key: SourceKey, load: suspend () -> IntArray?): SeedResult = withContext(io) {
        val cacheKey = cacheKey(key)
        cache.get(cacheKey, SEED_ALGORITHM_VERSION)?.let { return@withContext decode(it) }
        val pixels = load() ?: return@withContext SeedResult.Unavailable
        val result = extract(pixels)
        if (result != SeedResult.Unavailable) {
            cache.put(cacheKey, SEED_ALGORITHM_VERSION, encode(result))
        }
        result
    }

    companion object {
        fun cacheKey(key: SourceKey): String = "${key.value}|${key.sampleWidth}x${key.sampleHeight}"

        fun extract(pixels: IntArray): SeedResult {
            val opaque = pixels.filter { ((it ushr 24) and 0xFF) >= 250 }
            if (opaque.size < 4) return SeedResult.Unavailable
            val quantized = QuantizerCelebi.quantize(opaque.toIntArray(), 128)
            val ranked = Score.score(quantized, 1, NO_SEED, true)
            val first = ranked.firstOrNull() ?: NO_SEED
            return if (first == NO_SEED) SeedResult.LowChroma else SeedResult.Seed(first)
        }

        private fun encode(result: SeedResult): Int = when (result) {
            is SeedResult.Seed -> result.argb
            SeedResult.LowChroma, SeedResult.Unavailable -> -1
        }

        private fun decode(value: Int): SeedResult = when {
            value == -1 -> SeedResult.LowChroma
            value == 0 -> SeedResult.Unavailable
            else -> SeedResult.Seed(value)
        }
    }
}
