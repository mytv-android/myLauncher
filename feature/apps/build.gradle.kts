plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.github.mytv.feature.apps"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation(project(":data:apps"))
    implementation(libs.kotlinx.coroutines.android)
}
