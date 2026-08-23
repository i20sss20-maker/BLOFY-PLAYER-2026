plugins {
    id("com.android.application")
}

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Final TV + FFmpeg validation trigger.
val portalUrl = providers.gradleProperty("BLOFY_BASE_URL")
    .orElse(providers.environmentVariable("BLOFY_BASE_URL"))
    .orElse("https://blofy-player-2026-production.up.railway.app")

android {
    namespace = "tv.blofy.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.blofy.player"
        minSdk = 23
        targetSdk = 36
        versionCode = 2026082312
        versionName = "2026.08.23.12-final-tv"
        buildConfigField("String", "BLOFY_BASE_URL", quoted(portalUrl.get().trimEnd('/')))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            versionNameSuffix = "-test"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    val media3Version = "1.11.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-datasource-cronet:$media3Version")
    implementation("com.google.android.gms:play-services-cronet:18.0.1")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.zxing:core:3.5.4")

    val ffmpegDecoderAar = file("libs/media3-decoder-ffmpeg-release.aar")
    if (ffmpegDecoderAar.exists()) {
        implementation(files(ffmpegDecoderAar))
    }

    testImplementation("junit:junit:4.13.2")
}
