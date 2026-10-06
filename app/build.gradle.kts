import java.util.Properties   // NEW

plugins {
    alias(libs.plugins.android.application)
}

// NEW — reads ELEVENLABS_API_KEY from local.properties (never committed to git)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.givenvoice.wearable"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.givenvoice.wearable"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NEW — exposes the key to code as BuildConfig.ELEVENLABS_API_KEY
        buildConfigField(
            "String",
            "ELEVENLABS_API_KEY",
            "\"${localProps.getProperty("ELEVENLABS_API_KEY", "")}\""
        )
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true   // NEW — required for BuildConfig
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.usb.serial)   // ESP32 switch box over USB OTG
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation(fileTree("libs"))
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")   // required for repeatOnLifecycle
}