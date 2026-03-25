import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun keystoreValue(name: String): String? =
    keystoreProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

val hasLocalSigningConfig = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword",
).all { keystoreValue(it) != null }

val isReleaseTaskRequested = gradle.startParameter.taskNames.any { taskName ->
    val normalized = taskName.lowercase()
    normalized.contains("release") || normalized.contains("bundle")
}

if (isReleaseTaskRequested && !hasLocalSigningConfig) {
    throw GradleException(
        "Release build requires keystore.properties with storeFile/storePassword/keyAlias/keyPassword."
    )
}

android {
    namespace = "com.minou.pedometer"
    compileSdk = 34

    sourceSets {
        getByName("main") {
            // リポジトリの web/ を APK に同梱（GitHub Pages 非依存）
            assets.srcDir(rootProject.file("web"))
        }
    }

    defaultConfig {
        applicationId = "com.minou.pedometer"
        minSdk = 26
        targetSdk = 34
        versionCode = 20260326
        versionName = "2026.03.26-cream-ui"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasLocalSigningConfig) {
            create("localUnified") {
                storeFile = file(requireNotNull(keystoreValue("storeFile")))
                storePassword = requireNotNull(keystoreValue("storePassword"))
                keyAlias = requireNotNull(keystoreValue("keyAlias"))
                keyPassword = requireNotNull(keystoreValue("keyPassword"))
            }
        }
    }

    buildTypes {
        debug {
            if (hasLocalSigningConfig) {
                signingConfig = signingConfigs.getByName("localUnified")
            }
        }

        release {
            isMinifyEnabled = false
            if (hasLocalSigningConfig) {
                signingConfig = signingConfigs.getByName("localUnified")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    val roomVersion = "2.6.1"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
