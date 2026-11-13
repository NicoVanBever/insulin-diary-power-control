plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

// Your own signing key (keep insulindiary-signing.jks + keystore.properties safe: updates must be signed with it).
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.insulindiary.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.insulindiary.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.7"
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) {
            create("own") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        val own = signingConfigs.findByName("own") ?: signingConfigs.getByName("debug")
        debug { signingConfig = own }
        release {
            isMinifyEnabled = false
            signingConfig = own
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5") // newer than the one Play services pulls in (needed for file picking)
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real JSON for plain JVM unit tests
}
