plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dewangga.amplo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dewangga.amplo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // The release key is kept outside the repository. Its path and passwords come from
    // ~/.gradle/gradle.properties. Without them, the release APK is not signed.
    val releaseStore = providers.gradleProperty("AMPLO_RELEASE_STORE_FILE").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("AMPLO_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("AMPLO_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("AMPLO_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val compose = "1.9.0"
    val media3 = "1.8.0"
    val lifecycle = "2.9.4"

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycle")
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling:$compose")

    testImplementation("junit:junit:4.13.2")
    // Android has org.json, but the unit tests run on the computer, without it.
    testImplementation("org.json:json:20240303")
}
