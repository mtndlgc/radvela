import java.util.Properties

plugins {
    id("com.android.application")
}

// Yükleme anahtarı depoya girmez: kökte keystore.properties yoksa sürüm yapısı imzasız üretilir.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.otoradyo"
    compileSdk = 37
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "app.otoradyo"
        // Araç ekranları genelde eski Android sürümleri çalıştırır (DiLink 3: Android 10); yerel kod Android 8.0'a (API 26) kadar iner.
        minSdk = 26
        versionCode = 12
        versionName = "1.0.0"

        ndk {
            // Çekirdek NEON'lu 64 bit ARM'da gerçek zamanlı çalışır; tüm yerel kod kaynaktan derlenir.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    flavorDimensions += "store"
    productFlavors {
        // Google Play: güncel hedef API
        create("play") {
            dimension = "store"
            targetSdk = 36
            // AAC cihazın lisanslı çözücüsüyle çözülür: pakette FAAD2 yok
            externalNativeBuild {
                cmake {
                    arguments += "-DOTO_SYSTEM_AAC_ONLY=ON"
                }
            }
        }
        // BYD DiLink 3 (Android 10) yan yükleme: targetSdk <= 33, minSdk <= 29, yalnız ARM64.
        // DiLink'te cihaz çözücüsü denenene kadar FAAD2 yedek olarak kalır.
        create("sideload") {
            dimension = "store"
            targetSdk = 33
            versionNameSuffix = "-car"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    bundle {
        language {
            // Uygulama içi dil seçimi var: tüm diller pakette kalmalı
            enableSplit = false
        }
    }

    signingConfigs {
        create("upload") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("upload")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
