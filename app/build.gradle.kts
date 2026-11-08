import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * Play Store upload key. Never committed. Read from either:
 *  - environment variables (GitHub Actions): UPLOAD_KEYSTORE_FILE, UPLOAD_STORE_PASSWORD,
 *    UPLOAD_KEY_ALIAS, UPLOAD_KEY_PASSWORD
 *  - or a local keystore.properties file (git-ignored) with storeFile, storePassword,
 *    keyAlias, keyPassword
 */
val uploadProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun upload(env: String, prop: String): String? = System.getenv(env) ?: uploadProps.getProperty(prop)
val uploadStore = upload("UPLOAD_KEYSTORE_FILE", "storeFile")

/* Version: one place, version.properties at the repo root. */
val appVersionName: String = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}.getProperty("VERSION_NAME")
val appVersionCode: Int = appVersionName.split(".").map { it.toInt() }.let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "MINOR and PATCH must be below 100 (got $appVersionName)" }
    major * 10000 + minor * 100 + patch
}

android {
    namespace = "com.athulsn10.glancekit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.athulsn10.glancekit"
        minSdk = 31
        targetSdk = 36
        // From version.properties: 1.19.0 -> versionCode 11900.
        versionCode = appVersionCode
        versionName = appVersionName
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
        if (uploadStore != null) {
            create("upload") {
                storeFile = file(uploadStore)
                storePassword = upload("UPLOAD_STORE_PASSWORD", "storePassword")
                keyAlias = upload("UPLOAD_KEY_ALIAS", "keyAlias")
                keyPassword = upload("UPLOAD_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // Signed with your private upload key when it's available (for the Play Store);
            // otherwise with the test key, so a local release build still installs.
            signingConfig = signingConfigs.getByName(if (uploadStore != null) "upload" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
