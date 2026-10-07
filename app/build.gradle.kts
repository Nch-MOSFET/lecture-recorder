import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 署名鍵の場所とパスワードは Git に入れない keystore.properties から読む
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "local.lecturerecorder"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "local.lecturerecorder"
        minSdk = 31
        targetSdk = 36
        versionCode = 14
        versionName = "1.5.2"
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreProps.getProperty("storeFile")?.let { file(it) }
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // APK に Git のコミット情報を入れない
            vcsInfo.include = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android の org.json はテスト時に中身がないため、本物を使う
    testImplementation("org.json:json:20240303")
}
