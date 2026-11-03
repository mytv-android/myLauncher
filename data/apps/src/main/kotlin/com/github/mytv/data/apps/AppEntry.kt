package com.github.mytv.data.apps

import android.graphics.Bitmap

data class AppEntry(
    val packageName: String,
    val className: String,
    val label: String,
    val icon: Bitmap?,
    val banner: Bitmap?,
) {
    val tag: String
        get() = "app:$packageName/$className"
}

data class Folder(
    val id: String,
    val name: String,
    val apps: List<String>,
)
