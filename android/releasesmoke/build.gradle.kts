plugins { alias(libs.plugins.android.test) }

android {
    namespace = "com.grayvines.roche.releasesmoke"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("release") {
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    testOptions {
        managedDevices {
            localDevices {
                create("pixel10ProApi37") {
                    device = "Pixel 10 Pro"
                    apiLevel = 37
                    systemImageSource = "google"
                }
            }
        }
    }
}

kotlin { compilerOptions { allWarningsAsErrors = true } }

dependencies {
    implementation(libs.androidx.test.core.ktx)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
