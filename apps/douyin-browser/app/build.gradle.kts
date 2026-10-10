plugins {
    id("com.android.application")
}

android {
    namespace = "com.codex.x3douyin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.codex.x3douyin"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}
