plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.juya.vitrine"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.juya.vitrine"
        minSdk = 26
        targetSdk = 36   // Google Play: 2026년 8월 31일부터 새 앱·업데이트는 API 36 이상
        // 빌드할 때마다 번호가 자동으로 올라감 (Play 스토어는 올릴 때마다 더 큰 번호가 필요)
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.0"
        // AI 사진 서버 주소와 앱 열쇠 (GitHub 비밀값에서 빌드할 때 넣음)
        buildConfigField("String", "AI_URL", "\"" + (System.getenv("AI_URL") ?: "") + "\"")
        buildConfigField("String", "APP_KEY", "\"" + (System.getenv("APP_KEY") ?: "") + "\"")
        // 테스트용: 서버 없이 앱이 직접 그림 AI를 부름. 서버 주소(AI_URL)를 넣으면 키는 앱에서 자동으로 빠짐
        buildConfigField("String", "GEMINI_KEY", "\"" + (if ((System.getenv("AI_URL") ?: "").isNotBlank()) "" else (System.getenv("GEMINI_API_KEY") ?: "")) + "\"")
    }

    // 테스트 APK가 매번 같은 서명을 갖도록 고정 키 사용 (그래야 위에 덮어서 업데이트됨)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        val ksPath = System.getenv("UPLOAD_KEYSTORE")
        if (ksPath != null && file(ksPath).exists()) {
            create("upload") {
                storeFile = file(ksPath)
                storePassword = System.getenv("UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        // 웹 화면 파일(assets)은 압축하지 않음
        noCompress += listOf("html")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.android.billingclient:billing:8.0.0")
}
