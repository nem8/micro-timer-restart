plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.android")
}

android {
    compileSdk = 34

    defaultConfig {
        applicationId = "dubrowgn.microtimer"
        minSdk = 28 // Handler.postDelayed()
        // work around unused library resources
        resourceConfigurations.addAll(listOf("anydpi", "en"))
        targetSdk = 34
        versionCode = 12
        versionName = "2.0"
        applicationIdSuffix = ".restart"
    }

    // Fixed signing key so every build installs as an update over the last.
    // The key file is in the repo but encrypted; its password comes from the
    // KEYSTORE_PASSWORD secret in GitHub Actions. Without it (e.g. a local
    // build) the normal per-machine debug key is used instead.
    val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() }
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("signing/release.p12")
            storeType = "PKCS12"
            storePassword = keystorePassword
            keyAlias = "microtimer"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        debug {
            if (keystorePassword != null)
                signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    namespace = "dubrowgn.microtimer"
}

dependencies {
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    annotationProcessor("androidx.room:room-compiler:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.core:core-ktx:1.12.0")
}
