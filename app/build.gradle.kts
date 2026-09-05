plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ikegami99.semanticcompressor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ikegami99.semanticcompressor"
        minSdk = 28
        targetSdk = 36
        versionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: 3
        versionName = System.getenv("APP_VERSION_NAME") ?: "0.1.2"
    }

    val personalKeystore = rootProject.file("signing/semantic-personal.keystore")
    val personalSigning = if (personalKeystore.exists()) {
        signingConfigs.create("personal") {
            storeFile = personalKeystore
            storePassword = "semantic1234"
            keyAlias = "semantic"
            keyPassword = "semantic1234"
        }
    } else {
        null
    }

    buildTypes {
        getByName("debug") {
            personalSigning?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
