import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
val apiBaseUrl = localProperties.getProperty("API_BASE_URL", "http://10.0.2.2:8000/")
// SHA-256("clearflow-admin-v1:" + PIN). Override in local.properties (ADMIN_PIN_HASH=...) so the real PIN never ships in source.
val adminPinHash = localProperties.getProperty(
    "ADMIN_PIN_HASH", "c38a3d0d4a1fc5f2538d1445ede4008f3bf69833f13417ca107887ff3ea5fa37")

android {
    namespace = "com.um.clearflow"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.um.clearflow"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "ADMIN_PIN_HASH", "\"$adminPinHash\"")
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all { it.forkEvery = 1 }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.osmdroid.android)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation("androidx.work:work-runtime:2.10.0")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
