package com.github.mytv.data.series

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object SeriesTrust {
    fun expectedCerts(): Map<String, String> = SeriesTrustEntries.trusted

    fun isTrustedPackage(packageName: String): Boolean =
        SeriesTrustEntries.trusted.containsKey(packageName)

    fun isTrusted(context: Context, packageName: String): Boolean {
        val expected = SeriesTrustEntries.trusted[packageName] ?: return false
        if (expected.isBlank()) return false
        return certDigest(context, packageName)?.equals(expected, ignoreCase = true) == true
    }

    fun certDigest(context: Context, packageName: String): String? {
        val pm = context.packageManager
        val certs = try {
            if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures
            }
        } catch (_: Exception) {
            null
        } ?: return null
        val cert = certs.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(cert.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
