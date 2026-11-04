package com.github.mytv.core.install

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers T20: split selection, `.xapk` parsing and install prechecks (§14).
 */
class SplitSelectorTest {
    private val device = SimpleDeviceFacts(
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
        densityDpi = 320,
        languages = listOf("zh-CN", "en-US"),
        sdkInt = 31,
    )

    private fun split(
        name: String?,
        kind: SplitKind,
        file: String = "${name ?: "base"}.apk",
        packageName: String = "com.example.app",
        versionCode: Long = 100,
        signature: String = "sig-a",
        minSdk: Int? = 23,
        abi: String? = null,
        densityDpi: Int? = null,
        language: String? = null,
        bundledAbis: List<String> = emptyList(),
    ): SplitApk = SplitApk(
        file = File(file),
        splitName = name,
        kind = kind,
        packageName = packageName,
        versionCode = versionCode,
        signatureSha256 = signature,
        minSdk = minSdk,
        abi = abi,
        densityDpi = densityDpi,
        language = language,
        bundledAbis = bundledAbis,
    )

    private fun base(
        file: String = "base.apk",
        versionCode: Long = 100,
        signature: String = "sig-a",
        minSdk: Int? = 23,
        bundledAbis: List<String> = emptyList(),
    ): SplitApk = split(
        name = null,
        kind = SplitKind.BASE,
        file = file,
        versionCode = versionCode,
        signature = signature,
        minSdk = minSdk,
        bundledAbis = bundledAbis,
    )

    private class FakeArchiveReader(
        private val entryNames: List<String> = emptyList(),
        private val texts: Map<String, String> = emptyMap(),
    ) : ArchiveReader {
        override fun entries(archive: File): List<String> = entryNames
        override fun readText(archive: File, entry: String): String? = texts[entry]
    }

    @Test
    fun baseAndEveryFeatureSplitAreMandatory() {
        val base = base()
        val feature1 = split("feature1", SplitKind.FEATURE, "feature1.apk")
        val feature2 = split("feature2", SplitKind.FEATURE, "feature2.apk")

        val result = SplitSelector(device).select(listOf(base, feature1, feature2))
        assertTrue(result is SelectionResult.Selected)
        val selected = result as SelectionResult.Selected
        assertEquals(
            listOf("base.apk", "feature1.apk", "feature2.apk"),
            selected.splits.map { it.file.name },
        )
        assertTrue("base is first", selected.splits.first().kind == SplitKind.BASE)
    }

    @Test
    fun abiSplitFollowsDeviceSupportedAbisOrder() {
        val base = base()
        val arm64 = split("config.arm64_v8a", SplitKind.ABI, "arm64.apk", abi = "arm64-v8a")
        val armv7 = split("config.armeabi_v7a", SplitKind.ABI, "armv7.apk", abi = "armeabi-v7a")

        val first = SplitSelector(device).select(listOf(base, armv7, arm64))
        assertEquals("arm64.apk", (first as SelectionResult.Selected).abi?.file?.name)

        val reversedDevice = device.copy(
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a"),
        )
        val second = SplitSelector(reversedDevice).select(listOf(base, armv7, arm64))
        assertEquals("armv7.apk", (second as SelectionResult.Selected).abi?.file?.name)
    }

    @Test
    fun missingAbiBlocksInstall() {
        val base = base()
        val x86 = split("config.x86_64", SplitKind.ABI, "x86.apk", abi = "x86_64")
        val blocked = SplitSelector(device).select(listOf(base, x86))
        assertTrue(blocked is SelectionResult.Blocked)
        assertEquals(InstallError.ABI, (blocked as SelectionResult.Blocked).error)

        // A monolithic base APK that only bundles unsupported libraries is blocked too.
        val monolithic = base(bundledAbis = listOf("x86", "x86_64"))
        val blocked2 = SplitSelector(device).select(listOf(monolithic))
        assertEquals(InstallError.ABI, (blocked2 as SelectionResult.Blocked).error)
    }

    @Test
    fun densitySplitIsNearestAtOrAboveElseHighest() {
        val base = base()
        val hdpi = split("config.hdpi", SplitKind.DENSITY, "hdpi.apk", densityDpi = 240)
        val xhdpi = split("config.xhdpi", SplitKind.DENSITY, "xhdpi.apk", densityDpi = 320)
        val xxhdpi = split("config.xxhdpi", SplitKind.DENSITY, "xxhdpi.apk", densityDpi = 480)
        val splits = listOf(base, hdpi, xhdpi, xxhdpi)

        val d280 = device.copy(densityDpi = 280)
        assertEquals(320, (SplitSelector(d280).select(splits) as SelectionResult.Selected).density?.densityDpi)

        val d320 = device.copy(densityDpi = 320)
        assertEquals(320, (SplitSelector(d320).select(splits) as SelectionResult.Selected).density?.densityDpi)

        val d600 = device.copy(densityDpi = 600)
        assertEquals(480, (SplitSelector(d600).select(splits) as SelectionResult.Selected).density?.densityDpi)
    }

    @Test
    fun languageSplitUsesSystemLanguageListAndIsSkippedWhenNothingMatches() {
        val splits = listOf(
            base(),
            split("config.en", SplitKind.LANGUAGE, "en.apk", language = "en"),
            split("config.zh", SplitKind.LANGUAGE, "zh.apk", language = "zh"),
            split("config.fr", SplitKind.LANGUAGE, "fr.apk", language = "fr"),
        )

        val zhFirst = device.copy(languages = listOf("zh-CN", "en-US"))
        assertEquals("zh", (SplitSelector(zhFirst).select(splits) as SelectionResult.Selected).language?.language)

        val frFirst = device.copy(languages = listOf("fr-FR", "en-US"))
        assertEquals("fr", (SplitSelector(frFirst).select(splits) as SelectionResult.Selected).language?.language)

        val unmatched = device.copy(languages = listOf("de-DE"))
        val selected = SplitSelector(unmatched).select(splits) as SelectionResult.Selected
        assertNull(selected.language)
    }

    @Test
    fun parsesXapkManifestAndObbPaths() {
        val manifest = """
            {
              "xapk_version": 2,
              "package_name": "com.example.game",
              "version_code": "421",
              "version_name": "4.2.1",
              "min_sdk_version": "24",
              "split_apks": [
                {"file": "com.example.game.apk", "id": "base"},
                {"file": "config.arm64_v8a.apk", "id": "config.arm64_v8a"}
              ],
              "expansions": [
                {"file": "main.1.com.example.game.obb", "install_path": "Android/obb/com.example.game"},
                {"file": "patch.1.com.example.game.obb"}
              ]
            }
        """.trimIndent()

        val reader = FakeArchiveReader(
            entryNames = listOf(
                "lib/arm64-v8a/libgame.so",
                "lib/x86/libgame.so",
                "manifest.json",
            ),
            texts = mapOf("manifest.json" to manifest),
        )
        val selector = SplitSelector(device, archives = reader)

        val parsed = selector.parseXapk(File("game.xapk"))
        assertTrue(parsed != null)
        parsed!!
        assertEquals("com.example.game", parsed.packageName)
        assertEquals(421L, parsed.versionCode)
        assertEquals("4.2.1", parsed.versionName)
        assertEquals(24, parsed.minSdk)
        assertEquals(listOf("base", "config.arm64_v8a"), parsed.apks.map { it.splitName })
        assertEquals(
            listOf(
                "Android/obb/com.example.game/main.1.com.example.game.obb",
                "Android/obb/com.example.game/patch.1.com.example.game.obb",
            ),
            parsed.obbs.map { it.installPath },
        )
        assertEquals(setOf("arm64-v8a", "x86"), selector.nativeAbisIn(File("game.apk")))
    }

    @Test
    fun splitsThatDisagreeAreUnreadable() {
        val base = base()

        val differentPackage = split("feature1", SplitKind.FEATURE, packageName = "com.other")
        assertEquals(
            InstallError.UNREADABLE,
            (SplitSelector(device).select(listOf(base, differentPackage)) as SelectionResult.Blocked).error,
        )

        val differentVersion = split("feature1", SplitKind.FEATURE, versionCode = 99)
        assertEquals(
            InstallError.UNREADABLE,
            (SplitSelector(device).select(listOf(base, differentVersion)) as SelectionResult.Blocked).error,
        )

        val differentSignature = split("feature1", SplitKind.FEATURE, signature = "sig-b")
        assertEquals(
            InstallError.UNREADABLE,
            (SplitSelector(device).select(listOf(base, differentSignature)) as SelectionResult.Blocked).error,
        )

        assertEquals(
            InstallError.UNREADABLE,
            (SplitSelector(device).select(emptyList()) as SelectionResult.Blocked).error,
        )
        val featureOnly = split("feature1", SplitKind.FEATURE)
        assertEquals(
            InstallError.UNREADABLE,
            (SplitSelector(device).select(listOf(featureOnly)) as SelectionResult.Blocked).error,
        )
    }

    @Test
    fun installedSignatureMismatchBlocksInstall() {
        val installed = InstalledApp("com.example.app", 50, "sig-other")
        val blocked = SplitSelector(device).select(listOf(base()), installed)
        assertTrue(blocked is SelectionResult.Blocked)
        assertEquals(InstallError.SIGNATURE, (blocked as SelectionResult.Blocked).error)
    }

    @Test
    fun lowerVersionThanInstalledBlocksInstall() {
        val installed = InstalledApp("com.example.app", 200, "sig-a")
        val blocked = SplitSelector(device).select(listOf(base(versionCode = 100)), installed)
        assertEquals(InstallError.DOWNGRADE, (blocked as SelectionResult.Blocked).error)

        val sameVersion = SplitSelector(device).select(
            listOf(base(versionCode = 200)),
            installed,
        )
        assertTrue(sameVersion is SelectionResult.Selected)
    }

    @Test
    fun highMinSdkBlocksInstall() {
        val blocked = SplitSelector(device).select(listOf(base(minSdk = 33)))
        assertTrue(blocked is SelectionResult.Blocked)
        assertEquals(InstallError.MIN_SDK, (blocked as SelectionResult.Blocked).error)

        val nullMinSdk = SplitSelector(device).select(listOf(base(minSdk = null)))
        assertTrue(nullMinSdk is SelectionResult.Selected)
    }

    @Test
    fun configSplitNamesAreClassified() {
        assertEquals(SplitKind.BASE, SplitSelector.kindOfSplit(null))
        assertEquals(SplitKind.BASE, SplitSelector.kindOfSplit("base"))
        assertEquals(SplitKind.ABI, SplitSelector.kindOfSplit("config.arm64_v8a"))
        assertEquals("arm64-v8a", SplitSelector.abiOfSplit("config.arm64_v8a"))
        assertEquals(SplitKind.ABI, SplitSelector.kindOfSplit("config.x86_64"))
        assertEquals("x86_64", SplitSelector.abiOfSplit("config.x86_64"))
        assertEquals(SplitKind.DENSITY, SplitSelector.kindOfSplit("config.xxhdpi"))
        assertEquals(480, SplitSelector.densityOfSplit("config.xxhdpi"))
        assertEquals(SplitKind.LANGUAGE, SplitSelector.kindOfSplit("config.zh"))
        assertEquals("zh", SplitSelector.languageOfSplit("config.zh"))
        assertEquals(SplitKind.FEATURE, SplitSelector.kindOfSplit("feature1"))
    }
}
