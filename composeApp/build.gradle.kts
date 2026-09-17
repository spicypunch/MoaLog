import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":app-shell"))
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)
}

android {
    val localProperties = Properties().apply {
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
    }
    val configuredApiBaseUrl = providers.gradleProperty("moalog.api.baseUrl.android")
        .orElse(providers.environmentVariable("MOALOG_API_BASE_URL_ANDROID"))
        .orNull?.takeIf(String::isNotBlank)
        ?: localProperties.getProperty("moalog.api.baseUrl.android")?.takeIf(String::isNotBlank)
    val configuredGoogleClientId = providers.gradleProperty("moalog.google.webClientId")
        .orElse(providers.environmentVariable("MOALOG_GOOGLE_WEB_CLIENT_ID"))
        .orNull?.takeIf(String::isNotBlank)
        ?: localProperties.getProperty("moalog.google.webClientId")?.takeIf(String::isNotBlank)

    namespace = "kr.jm.moalog"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        applicationId = "kr.jm.moalog"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["moalogApiBaseUrl"] = configuredApiBaseUrl ?: "https://api.example.invalid"
        manifestPlaceholders["moalogGoogleWebClientId"] = configuredGoogleClientId.orEmpty()
    }
    buildTypes {
        debug {
            manifestPlaceholders["moalogApiBaseUrl"] = configuredApiBaseUrl ?: "http://10.0.2.2:8080"
        }
    }

    val validateReleaseConfiguration = tasks.register("validateReleaseConfiguration") {
        doLast {
            val apiBaseUrl = configuredApiBaseUrl.orEmpty()
            require(apiBaseUrl.startsWith("https://") && "example.invalid" !in apiBaseUrl) {
                "Release requires moalog.api.baseUrl.android (or MOALOG_API_BASE_URL_ANDROID) with a real HTTPS URL"
            }
            require(configuredGoogleClientId?.isNotBlank() == true) {
                "Release requires moalog.google.webClientId (or MOALOG_GOOGLE_WEB_CLIENT_ID)"
            }
        }
    }
    tasks.matching { it.name == "preReleaseBuild" }.configureEach {
        dependsOn(validateReleaseConfiguration)
    }
}
