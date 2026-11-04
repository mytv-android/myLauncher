package com.github.mytv.core.diag

import android.content.Context
import android.os.Build

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val device: String,
    val fingerprint: String,
) {
    companion object {
        fun current(): DeviceInfo = DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            fingerprint = Build.FINGERPRINT.orEmpty(),
        )
    }
}

/** One entry of assets/devices.json after matching. */
data class DeviceRule(
    val manufacturer: String,
    val model: String,
    val device: String?,
    val fingerprintPrefix: String?,
    val conclusions: Map<String, String>,
)

/**
 * Device correction data from assets/devices.json, with ADB overrides applied on top.
 *
 * Matching is sequential: manufacturer and model compare case-insensitively and trimmed;
 * an entry that declares [DeviceRule.device] or [DeviceRule.fingerprintPrefix] only matches
 * when those also agree. The first matching entry wins.
 *
 * Overrides come from the ADB `device_profile` payload (`{...}` JSON) and take priority
 * over the bundled conclusions. An empty-string override removes the key.
 */
class DeviceProfile internal constructor(
    val info: DeviceInfo,
    val matchedRule: DeviceRule?,
    val matchedIndex: Int?,
    val overrides: Map<String, String>,
) {
    val matched: Boolean
        get() = matchedRule != null

    fun conclusion(key: String): String? {
        if (overrides.containsKey(key)) {
            return overrides[key]?.takeIf { it.isNotEmpty() }
        }
        return matchedRule?.conclusions?.get(key)
    }

    /** Effective conclusion map after applying overrides; empty values remove keys. */
    fun conclusions(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        matchedRule?.conclusions?.let(out::putAll)
        for ((key, value) in overrides) {
            if (value.isEmpty()) out.remove(key) else out[key] = value
        }
        return out
    }

    companion object {
        private const val ASSET_NAME = "devices.json"
        private const val EMPTY_PROFILES = "{\"profiles\":[]}"

        fun load(
            context: Context,
            overrides: Map<String, String> = emptyMap(),
            info: DeviceInfo = DeviceInfo.current(),
        ): DeviceProfile {
            val json = runCatching {
                context.applicationContext.assets.open(ASSET_NAME)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            }.getOrElse { EMPTY_PROFILES }
            return fromJson(json, overrides, info)
        }

        fun fromJson(
            json: String,
            overrides: Map<String, String> = emptyMap(),
            info: DeviceInfo = DeviceInfo.current(),
        ): DeviceProfile {
            val root = runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *>
                ?: return DeviceProfile(info, null, null, overrides)
            val profiles = root["profiles"] as? List<*> ?: emptyList<Any?>()
            profiles.forEachIndexed { index, raw ->
                val entry = raw as? Map<*, *> ?: return@forEachIndexed
                val manufacturer = entry.stringValue("manufacturer")?.trim().orEmpty()
                val model = entry.stringValue("model")?.trim().orEmpty()
                if (manufacturer.isEmpty() || model.isEmpty()) return@forEachIndexed
                if (!manufacturer.equals(info.manufacturer.trim(), ignoreCase = true)) return@forEachIndexed
                if (!model.equals(info.model.trim(), ignoreCase = true)) return@forEachIndexed

                val device = entry.stringValue("device")?.trim()?.takeIf { it.isNotEmpty() }
                if (device != null && !device.equals(info.device.trim(), ignoreCase = true)) {
                    return@forEachIndexed
                }
                val fingerprintPrefix =
                    (entry.stringValue("fingerprintPrefix") ?: entry.stringValue("fingerprint"))
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                if (fingerprintPrefix != null &&
                    !info.fingerprint.startsWith(fingerprintPrefix, ignoreCase = true)
                ) {
                    return@forEachIndexed
                }
                val rule = DeviceRule(
                    manufacturer = manufacturer,
                    model = model,
                    device = device,
                    fingerprintPrefix = fingerprintPrefix,
                    conclusions = entry["conclusions"].toStringMap(),
                )
                return DeviceProfile(info, rule, index, overrides)
            }
            return DeviceProfile(info, null, null, overrides)
        }

        /** Parses the ADB `device_profile` JSON object into string overrides. */
        fun parseOverrides(rawJson: String?): Map<String, String> {
            if (rawJson.isNullOrBlank()) return emptyMap()
            val root = runCatching { MiniJson.parse(rawJson) }.getOrNull() as? Map<*, *>
                ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for ((key, value) in root) {
                if (key !is String) continue
                out[key] = when (value) {
                    null -> ""
                    is String -> value
                    else -> value.toString()
                }
            }
            return out
        }
    }
}

private fun Map<*, *>.stringValue(key: String): String? =
    when (val value = this[key]) {
        null -> null
        is String -> value
        else -> value.toString()
    }

private fun Any?.toStringMap(): Map<String, String> {
    val source = this as? Map<*, *> ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    for ((key, value) in source) {
        if (key !is String || value == null) continue
        out[key] = value as? String ?: value.toString()
    }
    return out
}
