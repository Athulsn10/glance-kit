plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.glassring.widget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.glassring.widget"
        minSdk = 31
        targetSdk = 35
        // Each GitHub build gets a higher number, so phones accept it as an update.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // A fixed key kept in the repo, so every build can install over the last one.
        // Fine for a personal app; use a private key before publishing to a store.
        getByName("debug") {
            storeFile = file("glancekit.keystore")
            storePassword = "glancekit"
            keyAlias = "glancekit"
            keyPassword = "glancekit"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.compose.ui:ui-unit:1.7.6")
    implementation("androidx.compose.ui:ui-graphics:1.7.6")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
