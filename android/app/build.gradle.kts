// Описывает имя установки, версию, зависимости и варианты сборки Андроида.
// Проверочные файлы и подпись для магазина подключаются здесь отдельно.

plugins {
    id("com.android.application")
}

// Считывает четыре части подписи из окружения. Пароли и путь к ключу не хранятся в исходниках.
val uploadStore = providers.environmentVariable("KANDINSKY_ANDROID_KEYSTORE").orNull
val uploadStorePassword = providers.environmentVariable("KANDINSKY_ANDROID_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("KANDINSKY_ANDROID_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("KANDINSKY_ANDROID_KEY_PASSWORD").orNull
val uploadSigningValues =
    listOf(uploadStore, uploadStorePassword, uploadKeyAlias, uploadKeyPassword)

// Разрешает запуск без подписи магазина или с полным набором. Частичная настройка сразу даёт ошибку.
require(
    uploadSigningValues.all { it.isNullOrBlank() } ||
        uploadSigningValues.none { it.isNullOrBlank() }
) {
    "Set all four KANDINSKY_ANDROID signing variables to sign the store bundle."
}

android {
    buildFeatures { buildConfig = true }
    namespace = "dev.kirill.kandinsky.chat"
    compileSdk = 37
    buildToolsVersion =
        providers.gradleProperty("kandinsky.androidBuildTools").orElse("36.1.0").get()
    // Имя обычной установки сохраняет обновление поверх прежней версии. У отдельных проверок может
    // быть своё имя и свой адрес, чтобы они не обращались к рабочему аккаунту.
    defaultConfig {
        applicationId =
            providers
                .gradleProperty("kandinsky.test.applicationId")
                .orElse("dev.kirill.kandinsky.native")
                .get()
        val bridgeOrigin =
            providers
                .gradleProperty("kandinsky.bridgeOrigin")
                .orElse("https://206.223.248.85:8449")
                .get()
        require(bridgeOrigin.matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?")))
        buildConfigField("String", "KANDINSKY_BRIDGE_ORIGIN", "\"$bridgeOrigin\"")

        minSdk = 29
        targetSdk = 36
        versionCode =
            providers.gradleProperty("kandinsky.versionCode").map(String::toInt).orElse(20).get()
        versionName = providers.gradleProperty("kandinsky.versionName").orElse("0.1.16").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Добавляет большой набор только в проверку на устройстве. Выпускное приложение не несёт эти файлы.
    val standFixtures = rootProject.layout.projectDirectory.dir("../fixtures")
    sourceSets["androidTest"].assets.directories.add(standFixtures.asFile.path)
    androidResources { noCompress += listOf("mp4", "m4a", "gif") }
    val uploadSigning =
        if (!uploadStore.isNullOrBlank()) {
            signingConfigs.create("storeUpload") {
                storeFile = file(uploadStore)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        } else null
    // Разделяет обычную, отладочную и магазинную сборки. Конфигурация магазина использует свой ключ.
    buildTypes {
        debug {
            val measureCoverage = providers.gradleProperty("kandinsky.coverage")
                .map(String::toBooleanStrict)
                .orElse(false)
                .get()
            enableUnitTestCoverage = measureCoverage
            enableAndroidTestCoverage = measureCoverage
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        create("store") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = uploadSigning
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Собирает рабочие и проверочные библиотеки в отдельных группах. Зависимости проверок не нужны
// при обычном запуске.
dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.1")
}

// Эти пометки нужны при компиляции. Их можно исключить из пакета приложения.
configurations
    .matching { it.name.endsWith("RuntimeClasspath") }
    .configureEach { exclude(group = "org.jetbrains", module = "annotations") }
