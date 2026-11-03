plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.github.mytv.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.github.mytv.launcher"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
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

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(project(":data:apps"))
    implementation(project(":data:channels"))
    implementation(project(":feature:home"))
    implementation(project(":feature:apps"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
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
