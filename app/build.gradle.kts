plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Версию библиотеки (yt-dlp + ffmpeg для Android) можно поднять здесь
val youtubedlAndroid = "0.18.1"

android {
    namespace = "com.example.videodl"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.videodl"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Только 64-битные телефоны: так APK заметно меньше.
        // Для старых 32-битных добавьте: abiFilters.add("armeabi-v7a")
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // Python и ffmpeg лежат в APK как нативные библиотеки и должны распаковываться
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
