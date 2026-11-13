plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

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

    // Direct ADB client: SPAKE2 pairing, TLS transport, and Shell V2.
    implementation("com.flyfishxu:kadb:2.1.5")
    // mDNS discovery for pairing and connect endpoints.
    implementation("com.flyfishxu:kadb-mdns:2.1.4")
    // Android 8 requires a TLS 1.3 provider for Wireless Debugging pairing.
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
