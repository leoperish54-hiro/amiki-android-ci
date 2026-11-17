plugins {
    id("com.android.application")
}

val ciKeystorePath = System.getenv("AMIKI_KEYSTORE_PATH")
val ciStorePassword = System.getenv("AMIKI_KEYSTORE_PASSWORD")
val ciKeyAlias = System.getenv("AMIKI_KEY_ALIAS")
val ciKeyPassword = System.getenv("AMIKI_KEY_PASSWORD")
val hasCiSigning = listOf(
    ciKeystorePath,
    ciStorePassword,
    ciKeyAlias,
    ciKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.amiki.app"
    compileSdk = 36

    signingConfigs {
        if (hasCiSigning) {
            create("ci") {
                storeFile = file(ciKeystorePath!!)
                storePassword = ciStorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.amiki.app"
        minSdk = 23
        targetSdk = 36
        versionCode = providers.gradleProperty("amikiVersionCode")
            .orElse("1")
            .get()
            .toInt()
        versionName = "1.0"
    }

    buildTypes {
        getByName("debug") {
            if (hasCiSigning) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }
}

dependencies {
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("androidx.biometric:biometric:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
