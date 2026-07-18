plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.gooserelay.gooserelayvpn"
    compileSdk = 36
    val appVersionName = System.getenv("ANDROID_VERSION_NAME")
        ?.takeIf { it.isNotBlank() }
        ?: "1.7.1"
    val appVersionCode = System.getenv("ANDROID_VERSION_CODE")
        ?.toIntOrNull()
        ?: 1

    defaultConfig {
        applicationId = "com.gooserelay.gooserelayvpn"
        minSdk = 21
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // For debug builds: build universal APK (single APK with all ABIs)
            // For production, you can configure separate APKs if needed
            abiFilters.clear()
        }
    }

    signingConfigs {
        create("release") {
            val signingEnabled = System.getenv("ANDROID_SIGNING_ENABLED") == "true"
            val storeFilePath = System.getenv("ANDROID_KEYSTORE_PATH")
            val storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            val keyPassword = System.getenv("ANDROID_KEY_PASSWORD")

            if (signingEnabled) {
                if (storeFilePath.isNullOrBlank()) throw org.gradle.api.GradleException("ANDROID_KEYSTORE_PATH is required when ANDROID_SIGNING_ENABLED=true")
                if (storePassword.isNullOrBlank()) throw org.gradle.api.GradleException("ANDROID_KEYSTORE_PASSWORD is required when ANDROID_SIGNING_ENABLED=true")
                if (keyAlias.isNullOrBlank()) throw org.gradle.api.GradleException("ANDROID_KEY_ALIAS is required when ANDROID_SIGNING_ENABLED=true")
                if (keyPassword.isNullOrBlank()) throw org.gradle.api.GradleException("ANDROID_KEY_PASSWORD is required when ANDROID_SIGNING_ENABLED=true")
            }

            if (!storeFilePath.isNullOrBlank()) storeFile = file(storeFilePath)
            if (!storePassword.isNullOrBlank()) this.storePassword = storePassword
            if (!keyAlias.isNullOrBlank()) this.keyAlias = keyAlias
            if (!keyPassword.isNullOrBlank()) this.keyPassword = keyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val signingEnabled = System.getenv("ANDROID_SIGNING_ENABLED") == "true"
            signingConfig = if (signingEnabled) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            val abi = output.getFilter(com.android.build.OutputFile.ABI)
            output.outputFileName = when (abi) {
                null -> "GooseRelayVPN.apk"
                "universal" -> "GooseRelayVPN.apk"
                else -> "GooseRelayVPN-$abi.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    splits {
        abi {
            // Generate split APKs plus a universal APK.
            // CI debug upload selects only universal; release workflow uploads all.
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Go mobile library (built via gomobile bind)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-android-compiler:2.53.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room DB
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.11.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
