plugins {
    alias(libs.plugins.android.application)
}

fun localSetting(name: String) = providers.environmentVariable(name)
    .orElse(providers.gradleProperty(name))

val releaseToken = localSetting("POBOOK_ANDROID_RELAY_TOKEN")
val debugToken = localSetting("POBOOK_ANDROID_DEBUG_RELAY_TOKEN").orElse(releaseToken)
val uploadStore = localSetting("POBOOK_UPLOAD_STORE_FILE")
val uploadStorePassword = localSetting("POBOOK_UPLOAD_STORE_PASSWORD")
val uploadAlias = localSetting("POBOOK_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = localSetting("POBOOK_UPLOAD_KEY_PASSWORD")

fun tokenLiteral(value: String?): String {
    require(value.isNullOrEmpty() || (value.length >= 32 && value.all { it in '!'..'~' })) {
        "Invalid Po:book relay credential"
    }
    return "\"" + value.orEmpty().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.youngmin.pobook"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.youngmin.pobook"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    val uploadSigning = if (uploadStore.isPresent) {
        signingConfigs.create("upload") {
            storeFile = file(uploadStore.get())
            storePassword = uploadStorePassword.get()
            keyAlias = uploadAlias.get()
            keyPassword = uploadKeyPassword.get()
        }
    } else {
        null
    }

    buildTypes {
        debug {
            buildConfigField("String", "RELAY_TOKEN", tokenLiteral(debugToken.orNull))
            val host = if (localSetting("POBOOK_ANDROID_DEBUG_RELAY_TOKEN").isPresent) {
                "https://pobook-api-dev.fpmaps.com"
            } else {
                "https://pobook-api.fpmaps.com"
            }
            buildConfigField("String", "RELAY_URL", "\"$host\"")
        }
        release {
            signingConfig = uploadSigning
            buildConfigField("String", "RELAY_TOKEN", tokenLiteral(releaseToken.orNull))
            buildConfigField("String", "RELAY_URL", "\"https://pobook-api.fpmaps.com\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
