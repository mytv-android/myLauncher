plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.github.mytv.data.weather"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
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
    implementation(libs.kotlinx.coroutines.android)

    compileOnly("androidx.annotation:annotation:1.8.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.icu4j)
}
