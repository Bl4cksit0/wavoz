plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.bck.wavoz"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.bck.wavoz"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "3.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    signingConfigs {
        create("wavoz") {
            storeFile = file(System.getProperty("user.home") + "/android-dev/keys/wavoz.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("wavoz")
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
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
