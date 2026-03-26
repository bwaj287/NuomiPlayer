plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myapplication.automotive"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myapplication.automotive"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.5.0-dev"
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

dependencies {
    implementation(project(":shared"))
}
