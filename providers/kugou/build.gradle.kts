plugins {
    id("com.android.application")
}

android {
    namespace = "com.juren233.hle.providers.kugou"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.juren233.hyperlyricsenhanced.provider.pack.kugou"
        minSdk = 33
        targetSdk = 37
        versionCode = 20
        versionName = "1.0.19"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.proify.lyricon:provider:0.1.70")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    compileOnly("org.luckypray:dexkit:2.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
    testImplementation("io.github.proify.lyricon:provider:0.1.70")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
}
