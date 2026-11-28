import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.donglq.sleepmonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.donglq.sleepmonitor"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "1.2.3"
    }

    signingConfigs {
        val stableDebugStore = System.getenv("SLEEP_MONITOR_KEYSTORE_PATH")
        val stableDebugPassword = System.getenv("SLEEP_MONITOR_KEYSTORE_PASSWORD")
        val stableDebugAlias = System.getenv("SLEEP_MONITOR_KEY_ALIAS")
        val stableDebugKeyPassword = System.getenv("SLEEP_MONITOR_KEY_PASSWORD")
        if (!stableDebugStore.isNullOrBlank() && file(stableDebugStore).exists() &&
            !stableDebugPassword.isNullOrBlank() && !stableDebugAlias.isNullOrBlank() &&
            !stableDebugKeyPassword.isNullOrBlank()
        ) {
            create("stableDebug") {
                storeFile = file(stableDebugStore)
                storePassword = stableDebugPassword
                keyAlias = stableDebugAlias
                keyPassword = stableDebugKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (signingConfigs.findByName("stableDebug") != null) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.mediapipe:tasks-audio:0.10.35")
    debugImplementation("androidx.compose.ui:ui-tooling")
}


val yamnetModelFile = layout.projectDirectory.file("src/main/assets/yamnet.tflite")
val downloadYamnetModel by tasks.registering {
    outputs.file(yamnetModelFile)
    doLast {
        val target = yamnetModelFile.asFile
        if (!target.exists() || target.length() < 1_000_000L) {
            target.parentFile.mkdirs()
            val connection = URL(
                "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite"
            ).openConnection().apply {
                setConnectTimeout(30_000)
                setReadTimeout(120_000)
            }
            connection.getInputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            check(target.length() > 1_000_000L) { "YAMNet model download was incomplete" }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(downloadYamnetModel) }
