plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "vn.chieusang.logo"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.chieusang.logo"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // Dùng một khóa ký cố định để bản mới cài đè được lên bản cũ.
    signingConfigs {
        getByName("debug") {
            storeFile = file("logo-app.keystore")
            storePassword = "logoapp123"
            keyAlias = "logoapp"
            keyPassword = "logoapp123"
        }
    }
    buildTypes {
        getByName("debug") {
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
}

dependencies {
    // Thư viện MQTT (chế độ điều khiển từ xa qua cloud)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
