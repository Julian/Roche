plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
    alias(libs.plugins.baselineprofile)
}

val home: String = System.getProperty("user.home")
val defaultStore =
    if (System.getProperty("os.name").startsWith("Mac")) {
        "$home/Library/Application Support/Roche/release.p12"
    } else {
        "${System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"}/roche/release.p12"
    }
val releaseStore = providers.gradleProperty("rocheStoreFile").orElse(defaultStore).map(::file)

val versionNameFromTag =
    providers.gradleProperty("rocheVersionName").map { it.removePrefix("v") }.orElse("0.0-dev")
val commitCount =
    providers
        .exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }
        .standardOutput
        .asText
        .map { it.trim().toIntOrNull() ?: 1 }

val wantsRelease = gradle.startParameter.taskNames.any { "Release" in it }
val keychainPassword =
    if (wantsRelease) {
        providers
            .exec {
                commandLine(
                    "security",
                    "find-generic-password",
                    "-s",
                    "roche-release-signing",
                    "-w",
                )
                isIgnoreExitValue = true
            }
            .standardOutput
            .asText
            .map { it.trim() }
    } else {
        provider { "" }
    }
val releasePassword = providers.gradleProperty("rocheStorePassword").orElse(keychainPassword)

android {
    namespace = "com.grayvines.roche"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.grayvines.roche"
        minSdk = 36
        targetSdk = 37
        versionName = versionNameFromTag.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStore.get().exists() && wantsRelease) {
            val password = releasePassword.get()
            check(password.isNotEmpty()) {
                "${releaseStore.get()} exists but no password was found: neither the " +
                    "rocheStorePassword property nor a Keychain entry roche-release-signing."
            }
            create("release") {
                storeFile = releaseStore.get()
                storePassword = password
                keyAlias = "roche"
                keyPassword = password
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    lint {
        warningsAsErrors = true
        checkAllWarnings = true
        abortOnError = true
        checkDependencies = true
        disable += "NewerVersionAvailable"
        disable += "GradleDependency"
        disable += "AndroidGradlePluginVersion"
        disable += "InvalidPackage"
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
        managedDevices {
            localDevices {
                create("pixel9Api36") {
                    device = "Pixel 9"
                    apiLevel = 36
                    systemImageSource = "google"
                }
                create("pixel10ProApi37") {
                    device = "Pixel 10 Pro"
                    apiLevel = 37
                    systemImageSource = "google"
                }
            }
            groups {
                create("phones") {
                    targetDevices.add(localDevices["pixel9Api36"])
                    targetDevices.add(localDevices["pixel10ProApi37"])
                }
            }
        }
    }
}

androidComponents {
    onVariants { variant -> variant.outputs.forEach { it.versionCode.set(commitCount) } }
}

room3 { schemaDirectory("$projectDir/schemas") }

android.sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

val recordProfile = providers.gradleProperty("rocheProfile").isPresent

baselineProfile {
    automaticGenerationDuringBuild = recordProfile
    saveInSrc = !recordProfile
}

dependencies {
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    implementation(libs.room3.runtime)
    implementation(libs.sqlite.framework)
    ksp(libs.room3.compiler)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.bundled.jvm)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room3.testing)

    constraints {
        implementation(libs.kotlinx.serialization.core)
        implementation(libs.kotlinx.serialization.json)
    }
    debugImplementation(libs.compose.ui.test.manifest)
}
