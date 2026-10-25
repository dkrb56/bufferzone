plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One version for all platforms: the "version" in the root package.json.
val appVersion: String = Regex(""""version"\s*:\s*"([^"]+)""").find(rootDir.resolve("../package.json").readText())!!.groupValues[1]
val (vMajor, vMinor, vPatch) = appVersion.split(".").map { it.toInt() }

android {
    namespace = "com.dkrb56.bufferzone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dkrb56.bufferzone"
        minSdk = 24
        targetSdk = 36
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        versionName = appVersion
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
}
