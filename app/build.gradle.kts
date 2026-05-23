plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.vpbat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.vpbat"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("release") {
            storeFile   = file("release.keystore")
            storePassword = "vpbank2024"
            keyAlias    = "vpbank"
            keyPassword = "vpbank2024"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig   = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
}
