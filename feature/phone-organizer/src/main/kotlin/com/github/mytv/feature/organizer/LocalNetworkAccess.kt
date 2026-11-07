package com.github.mytv.feature.organizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Gate for the local network permission the transfer server needs (§14).
 *
 * Android 17 (API 37) blocks local network traffic for apps targeting API 37 or
 * higher — accepting an inbound TCP connection included — unless the app holds
 * ACCESS_LOCAL_NETWORK. The server listens on the LAN address and on 127.0.0.1
 * while the page is open; loopback stays reachable either way.
 *
 * Every decision takes the SDK level as a parameter so it can be checked on the
 * JVM; call sites use the default.
 */
object LocalNetworkAccess {
    const val PERMISSION: String = Manifest.permission.ACCESS_LOCAL_NETWORK
    private const val ENFORCED_SDK = 37

    /** True on Android 17 or higher, where the platform enforces the permission. */
    fun isEnforced(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= ENFORCED_SDK

    /** Granted, or not enforced at all — lower targets are granted implicitly. */
    fun isGranted(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        !isEnforced(sdkInt) ||
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Pure decision: ask only where the platform enforces it and it is missing. */
    fun shouldRequest(sdkInt: Int, granted: Boolean): Boolean = isEnforced(sdkInt) && !granted

    fun shouldRequest(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        shouldRequest(sdkInt, isGranted(context, sdkInt))
}
