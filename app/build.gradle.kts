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
        versionCode = 9
        versionName = "4.2"
        // Solo 32 bits: el Galaxy A13 (SM-A135M) corre Android de 32 bits y no puede usar arm64-v8a.
        // Los celulares de 64 bits con soporte de 32 también la ejecutan.
        ndk { abiFilters += listOf("armeabi-v7a") }
    }
    signingConfigs {
        create("wavoz") {
            storeFile = file(System.getProperty("user.home") + "/android-dev/keys/wavoz.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        release {
            // R8: achica el código (sobre todo los íconos de Material). Reglas de Vosk/JNA en proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("wavoz")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Compiler de Compose compatible con Kotlin 1.9.24
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")

    // UI: Jetpack Compose + Material 3
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
