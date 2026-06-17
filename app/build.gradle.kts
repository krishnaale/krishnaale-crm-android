plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "au.krishnaale.crm"
    compileSdk = 35

    defaultConfig {
        // IMPORTANT: applicationId is permanent once published to Google Play. Confirm before first upload.
        applicationId = "au.krishnaale.crm"
        minSdk = 26          // Android 8.0 — adaptive icons + notification channels, ~96% of active devices
        targetSdk = 35       // Android 15
        versionCode = 1
        versionName = "1.0.0"

        // The portal your clients log into. Centralised here so it is easy to change.
        buildConfigField("String", "PORTAL_URL", "\"https://krishnaale.agencyhandy.com/\"")

        // The host that in-app navigation is allowed to stay inside. Everything else
        // opens in an external browser / Custom Tab.
        buildConfigField("String", "PORTAL_HOST", "\"krishnaale.agencyhandy.com\"")

        // Your Firebase Cloud Function base URL for device registration.
        // Replace after you deploy the backend (see backend/README.md).
        buildConfigField(
            "String",
            "BACKEND_URL",
            "\"https://REGION-PROJECT.cloudfunctions.net\""
        )
    }

    signingConfigs {
        // A release keystore is required to upload to Google Play. Generate one with:
        //   keytool -genkey -v -keystore krishna-crm-release.jks -alias krishna -keyalg RSA -keysize 2048 -validity 10000
        // then fill in the values below (ideally via ~/.gradle/gradle.properties, not in source control).
        create("release") {
            val ksPath = (project.findProperty("KS_FILE") as String?) ?: "krishna-crm-release.jks"
            storeFile = file(ksPath)
            storePassword = (project.findProperty("KS_STORE_PASSWORD") as String?) ?: ""
            keyAlias = (project.findProperty("KS_KEY_ALIAS") as String?) ?: "krishna"
            keyPassword = (project.findProperty("KS_KEY_PASSWORD") as String?) ?: ""
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only sign with the real keystore when the properties are present,
            // otherwise a debug build / CI without secrets still assembles.
            val hasKeystore = (project.findProperty("KS_STORE_PASSWORD") as String?)?.isNotEmpty() == true
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        getByName("debug") {
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Modern WebView APIs (safe browsing, etc.)
    implementation("androidx.webkit:webkit:1.11.0")

    // Chrome Custom Tabs for external links (payments, mailto handled separately)
    implementation("androidx.browser:browser:1.8.0")

    // Biometric / device-credential app lock
    implementation("androidx.biometric:biometric:1.1.0")

    // Encrypted storage for the saved notification email
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Firebase Cloud Messaging (push)
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Lifecycle (used to detect foreground/background for the app lock timeout)
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")
}
