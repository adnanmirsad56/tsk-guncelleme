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
