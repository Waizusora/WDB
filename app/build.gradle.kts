plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningValues = listOf(
    System.getenv("WDB_KEYSTORE_PATH"),
    System.getenv("WDB_KEYSTORE_PASSWORD"),
    System.getenv("WDB_KEY_ALIAS"),
    System.getenv("WDB_KEY_PASSWORD")
)
val releaseSigningAvailable = releaseSigningValues.all { !it.isNullOrBlank() }

android {
    namespace = "com.waizusora.wdb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.waizusora.wdb"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.3.0"
    }

    buildFeatures { compose = true }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(requireNotNull(System.getenv("WDB_KEYSTORE_PATH")))
                storePassword = requireNotNull(System.getenv("WDB_KEYSTORE_PASSWORD"))
                keyAlias = requireNotNull(System.getenv("WDB_KEY_ALIAS"))
                keyPassword = requireNotNull(System.getenv("WDB_KEY_PASSWORD"))
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources {
            excludes += "META-INF/LICENSE.md"
        }
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
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    // Pure Kotlin ADB protocol, SPAKE2 pairing, TLS 1.3, Shell V2, and streaming APIs.
    implementation("com.github.rhythmcache:adb-kt:394dea235b4bfb2ebbfba5a821bbe4f6cd59e460")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
