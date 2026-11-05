plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keyframe.studio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.keyframe.studio"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"
    }

    buildTypes {
        // signed with the auto-generated debug key so it's directly
        // installable without Play Store signing; replace with a real
        // keystore for store distribution
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    dependenciesInfo {
        includeInApk = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
