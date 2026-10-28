import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI nastaví GITHUB_RUN_NUMBER, takže každé zostavenie má vyššie číslo verzie
// a dá sa nainštalovať ako aktualizácia cez predchádzajúcu.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "online.virivky.amap"
    compileSdk = 35

    defaultConfig {
        applicationId = "online.virivky.amap"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"

        // Mobily a autá používajú ARM; x86 knižnice mapy by len zväčšili APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Pevný kľúč v repozitári, aby sa nové verzie dali inštalovať cez staré.
        create("amap") {
            storeFile = file("amap.keystore")
            storePassword = "android"
            keyAlias = "amap"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("amap")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("amap")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-common:2.6.2")
    // Vektorová mapa (OpenGL variant – funguje aj pri kreslení na obrazovku auta).
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
}
