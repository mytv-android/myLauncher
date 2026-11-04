package com.github.mytv.data.restricted

/** Wall clock used by the restricted-mode policy classes. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        fun system(): Clock = Clock { System.currentTimeMillis() }
    }
}
