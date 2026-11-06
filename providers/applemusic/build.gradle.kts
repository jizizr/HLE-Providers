plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
}

android {
    namespace = "com.juren233.hle.providers.applemusic"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.juren233.hle.providers.pack.applemusic"
        minSdk = 33
        targetSdk = 37
        versionCode = 23
        versionName = "1.0.22"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // AGP rejects the non-optimize default file; optimization itself is turned off
            // by -dontoptimize in proguard-rules.pro.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.proify.lyricon:provider:0.1.70")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    compileOnly("org.luckypray:dexkit:2.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("io.github.proify.lyricon:provider:0.1.70")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}
