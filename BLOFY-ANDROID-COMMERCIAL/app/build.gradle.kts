plugins {
    id("com.android.application")
}

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val blofyUrl = providers.gradleProperty("BLOFY_BASE_URL")
    .orElse(providers.environmentVariable("BLOFY_BASE_URL"))
    .orElse("https://blofy-player-2026-production.up.railway.app")

android {
    namespace = "tv.blofy.commercial"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.blofy.player"
        minSdk = 23
        targetSdk = 36
        versionCode = 2026082111
        versionName = "2026.08.21.11-commercial"
        buildConfigField("String", "BLOFY_BASE_URL", quoted(blofyUrl.get().trimEnd('/')))
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        debug { }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
        // BLOFY uses a date-based monotonic code (yyyyMMddRR) so existing TV
        // installations can update in place. It remains below Play's limit.
        disable += "HighAppVersionCode"
    }
}

dependencies {
    val media3 = "1.11.0"
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
}
