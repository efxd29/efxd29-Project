plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.efxd29.pterodactyl"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.efxd29.pterodactyl"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging.resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
}
