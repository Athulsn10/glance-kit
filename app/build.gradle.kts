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

/*
 * Firebase (People widget). Read from app/google-services.json when it's there, so the app still
 * builds without it: People then shows "not set up". No Google Services Gradle plugin needed.
 */
val firebase: Map<String, String> = run {
    val f = file("google-services.json")
    if (!f.exists()) return@run emptyMap()
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val project = json["project_info"] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val clients = json["client"] as List<Map<String, Map<String, Any?>>>
    val client = clients.firstOrNull {
        @Suppress("UNCHECKED_CAST")
        (it["client_info"]?.get("android_client_info") as? Map<String, Any?>)?.get("package_name") == "com.athulsn10.glancekit"
    } ?: return@run emptyMap()
    @Suppress("UNCHECKED_CAST")
    val keys = client["api_key"] as? List<Map<String, Any?>> ?: return@run emptyMap()
    mapOf(
        "PROJECT_ID" to project["project_id"].toString(),
        "SENDER_ID" to project["project_number"].toString(),
        "APP_ID" to client["client_info"]?.get("mobilesdk_app_id").toString(),
        "API_KEY" to keys.first()["current_key"].toString(),
    )
}

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

        for (k in listOf("PROJECT_ID", "SENDER_ID", "APP_ID", "API_KEY")) {
            buildConfigField("String", "FIREBASE_$k", "\"${firebase[k] ?: ""}\"")
        }
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

    buildFeatures {
        buildConfig = true
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

    // People widget: anonymous sign-in, a tiny database, push for hugs and pokes.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
