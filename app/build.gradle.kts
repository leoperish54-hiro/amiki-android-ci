import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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

val sherpaAar = layout.buildDirectory.file("third-party/sherpa-onnx-2.0.1.aar")
val sherpaAarUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"
val sherpaAarSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = file.inputStream()
    try {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    } finally {
        input.close()
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/*
 * The upstream Android AAR is fetched only during builds and is SHA-256 pinned.
 * This keeps the native dependency out of both source repositories while
 * ensuring CI and local builds resolve the exact same official release.
 */
val downloadSherpaOnnxAar = tasks.register("downloadSherpaOnnxAar") {
    outputs.file(sherpaAar)
    outputs.upToDateWhen { false }
    doLast {
        val destination = sherpaAar.get().asFile
        if (destination.isFile && sha256(destination) == sherpaAarSha256) {
            return@doLast
        }
        destination.parentFile.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        if (temporary.exists()) temporary.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = URL(sherpaAarUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            connection.setRequestProperty("User-Agent", "Amiki-Android-Build/1.0")
            connection.setRequestProperty("Accept", "application/octet-stream")
            val status = connection.responseCode
            if (status !in 200..299) {
                throw GradleException("sherpa-onnx download failed with HTTP $status")
            }
            val input = connection.inputStream
            val output = FileOutputStream(temporary)
            try {
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } finally {
                try {
                    output.close()
                } finally {
                    input.close()
                }
            }

            val actual = sha256(temporary)
            if (actual != sherpaAarSha256) {
                throw GradleException("sherpa-onnx AAR SHA-256 mismatch: $actual")
            }
            if (destination.exists() && !destination.delete()) {
                throw GradleException("Could not replace the existing sherpa-onnx AAR")
            }
            if (!temporary.renameTo(destination)) {
                throw GradleException("Could not publish the verified sherpa-onnx AAR")
            }
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        } finally {
            connection?.disconnect()
        }
    }
}

val sherpaAarDependency = files(sherpaAar).builtBy(downloadSherpaOnnxAar)

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
    implementation(sherpaAarDependency)
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

