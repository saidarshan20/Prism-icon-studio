plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "local.iconstudio"
    compileSdk = 36
    defaultConfig { applicationId = "local.iconstudio"; minSdk = 28; targetSdk = 35; versionCode = 10; versionName = "0.10-review-navigation"; testInstrumentationRunner = "local.iconstudio.ProofInstrumentation" }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
    val releasePassword = System.getenv("ICONSTUDIO_STORE_PASSWORD")
    signingConfigs {
        if (!releasePassword.isNullOrBlank()) {
            create("personal") {
                storeFile = file(System.getenv("ICONSTUDIO_KEYSTORE") ?: "../secrets/release.jks")
                storePassword = releasePassword
                keyAlias = System.getenv("ICONSTUDIO_KEY_ALIAS") ?: "studio"
                keyPassword = System.getenv("ICONSTUDIO_KEY_PASSWORD") ?: releasePassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (!releasePassword.isNullOrBlank()) signingConfig = signingConfigs.getByName("personal")
            isMinifyEnabled = false
        }
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.android.tools.build:apksig:8.13.1")
}
