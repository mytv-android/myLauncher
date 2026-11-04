package com.github.mytv.core.dynamiccolor

interface SeedCache {
    suspend fun get(key: String, algorithmVersion: Int): Int?

    suspend fun put(key: String, algorithmVersion: Int, result: Int)
}

const val SEED_ALGORITHM_VERSION = 1
const val NO_SEED = 0
