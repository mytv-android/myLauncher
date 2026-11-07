import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("signing.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val localKeystore = rootProject.file("keystore.jks")
val userKeystore = project.file(
    System.getenv("KEYSTORE") ?: keystoreProperties.getProperty("storeFile")
        ?: "keystore.jks"
)
val hasReleaseKeystore = userKeystore.exists() || localKeystore.exists()

android {
    namespace = "com.github.mytv.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.github.mytv.launcher"
        minSdk = 23
        targetSdk = 37
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "0.1.0"
    }

    flavorDimensions += "dist"
    productFlavors {
        create("standard") {
            dimension = "dist"
        }
        create("oem") {
            dimension = "dist"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            storeFile = if (userKeystore.exists()) userKeystore else localKeystore
            storePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: keystoreProperties.getProperty("storePassword")
            keyAlias = System.getenv("KEY_ALIAS") ?: keystoreProperties.getProperty("keyAlias")
            keyPassword = System.getenv("KEY_PASSWORD") ?: keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:tvfocus"))
    implementation(project(":core:glass"))
    implementation(project(":core:dynamiccolor"))
    implementation(project(":core:ambient"))
    implementation(project(":data:apps"))
    implementation(project(":data:channels"))
    implementation(project(":data:series"))
    implementation(project(":data:widgets"))
    implementation(project(":data:weather"))
    implementation(project(":data:media"))
    implementation(project(":data:files"))
    implementation(project(":data:restricted"))
    implementation(project(":core:diag"))
    implementation(project(":core:install"))
    implementation(project(":feature:home"))
    implementation(project(":feature:apps"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:quicksettings"))
    implementation(project(":feature:widgets"))
    implementation(project(":feature:phone-organizer"))
    implementation(project(":feature:viewer"))
    implementation(project(":opt:shizuku"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

configurations.all {
    resolutionStrategy {
        force("androidx.compose.material3:material3:1.5.0-alpha25")
        force("androidx.compose.ui:ui:1.12.1")
        force("androidx.compose.ui:ui-graphics:1.12.1")
        force("androidx.compose.foundation:foundation:1.12.1")
        force("androidx.compose.runtime:runtime:1.12.1")
    }
}
