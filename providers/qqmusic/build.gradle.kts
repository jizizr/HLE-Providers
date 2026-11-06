plugins {
    id("com.android.application")
}

android {
    namespace = "com.juren233.hle.providers.qqmusic"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.juren233.hle.providers.pack.qqmusic"
        minSdk = 33
        targetSdk = 37
        versionCode = 32
        versionName = "1.0.31"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        // 换算器阶段日志走 android.util.Log（release 可见）；JVM 单测走到日志路径时
        // 以默认返回值放行，只测分支逻辑
        unitTests.isReturnDefaultValues = true
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
    testImplementation("io.github.proify.lyricon:provider:0.1.70")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    testImplementation("org.json:json:20180813")
}
