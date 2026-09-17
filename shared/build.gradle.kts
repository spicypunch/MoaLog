import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SharedKit"
            isStatic = true
            binaryOption("bundleId", "kr.jm.moalog.shared")
            export(project(":core:model"))
            export(project(":feature:home"))
            export(project(":feature:setup"))
            export(project(":feature:records"))
            export(project(":feature:plan"))
            export(project(":feature:assets"))
            export(project(":feature:maintenance"))
            export(libs.kotlinx.coroutines.core)
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(project(":core:database"))
            implementation(project(":core:contracts"))
            implementation(project(":core:network"))
            api(project(":feature:home"))
            api(project(":feature:setup"))
            api(project(":feature:records"))
            api(project(":feature:plan"))
            api(project(":feature:assets"))
            api(project(":feature:maintenance"))
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.android)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.test)
        }
    }
}

android {
    namespace = "kr.jm.moalog.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
