plugins {
    id("com.android.application")
}

// 纯 Java + 零第三方依赖：
//   · 不引入 Kotlin 运行时 / AndroidX / Material，APK 体积最小
//   · 只依赖 Android Framework，任意 Android Studio 版本都能直接同步
android {
    namespace = "com.ledmatrix.marquee"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ledmatrix.marquee"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    // 无第三方依赖
}