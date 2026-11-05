plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.gsmturkey.tsksms"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.gsmturkey.tsksms"
        minSdk = 23
        targetSdk = 34
        versionCode = (System.getenv("SMS_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("SMS_VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("SMS_KEYSTORE_FILE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("SMS_KEYSTORE_PASS")
                keyAlias = System.getenv("SMS_KEY_ALIAS") ?: "tsksms"
                keyPassword = System.getenv("SMS_KEYSTORE_PASS")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    // 1.4: internet üzerinden SMS — köprüye WebSocket bağlantısı
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 1.7: uygulama içinden QR okutma (Google kod tarayıcısı; kamera izni gerekmez)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
