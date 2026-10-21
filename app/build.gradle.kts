import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.walkquest"
    compileSdk = 36

    // Свойства окружения (не коммитятся): supabase (эпик 5), release-keystore (6.4).
    fun localProperty(key: String): String {
        val file = rootProject.file("local.properties")
        if (!file.isFile) return ""
        return runCatching {
            Properties().apply { load(file.inputStream()) }.getProperty(key, "")
        }.getOrDefault("")
    }

    defaultConfig {
        applicationId = "app.walkquest"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    defaultConfig {
        // Лидерборд (эпик 5): из local.properties. Пусто — экран «не настроено».
        val supabaseUrl = localProperty("supabase.url")
        val supabaseKey = localProperty("supabase.anon.key")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseKey\"")
    }

    signingConfigs {
        // Релизная подпись (6.4): keystore владельца из local.properties.
        // Не настроено — storeFile остаётся null и release подписывается debug-ключом
        // (как до 6.4; для Play нужен свой ключ — инструкция docs/release-signing.md).
        create("release") {
            val keystorePath = localProperty("release.keystore")
            if (keystorePath.isNotBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = localProperty("release.keystore.password")
                keyAlias = localProperty("release.key.alias")
                keyPassword = localProperty("release.key.password")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // AD-8: целевой артефакт гейта 1.12 — release arm64 APK ≤ 30 МБ.
            isMinifyEnabled = true
            isShrinkResources = true
            // AD-8: вместо universal APK в релиз идёт только arm64-v8a
            // (основная целевая платформа; ~46,5 МБ из debug universal —
            // это 4 ABI-копии libmaplibre.so). Debug-сборку это не трогает.
            ndk {
                abiFilters += "arm64-v8a"
            }
            // 6.4: release-подпись из local.properties; keystore владельца ещё нет —
            // честный fallback на debug (сборка и устройство не ломаются).
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // AD-1: MapLibre Native, артефакт по умолчанию (Vulkan).
    implementation(libs.maplibre.android.sdk)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Тестовых зависимостей нет и не будет: юнит- и UI-тесты в проекте запрещены
    // (см. AGENTS.md). Проверка — только через эмулятор/телефон, см. _qa/wq.py.
}
