plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: {release.keystore, credentials.env}, looked for in the project root
// first, then the canonical store at ~/sandbox/.signing/geo-trail-maps. Override with
// -PgeoTrailMaps.signingDir=... or GEO_TRAIL_MAPS_SIGNING_DIR. Without a key the release
// build falls back to the debug key: it runs, but won't install over a release build.
val signingDir: File = listOfNotNull(
    findProperty("geoTrailMaps.signingDir") as String?,
    System.getenv("GEO_TRAIL_MAPS_SIGNING_DIR"),
    rootProject.projectDir.path,
    "${System.getProperty("user.home")}/sandbox/.signing/geo-trail-maps",
).map(::file).firstOrNull { File(it, "credentials.env").isFile } ?: file(".")

val signingCreds: Map<String, String> = File(signingDir, "credentials.env")
    .takeIf { it.isFile }
    ?.readLines()
    ?.filter { it.contains("=") && !it.trimStart().startsWith("#") }
    ?.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
    .orEmpty()

android {
    namespace = "org.geotrailmaps"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.geotrailmaps"
        minSdk = 37
        targetSdk = 37
        versionCode = 15
        versionName = "0.8.1"
    }

    signingConfigs {
        if (signingCreds.isNotEmpty()) {
            create("release") {
                storeFile = File(signingDir, signingCreds.getValue("KEYSTORE_FILE"))
                storePassword = signingCreds.getValue("KEYSTORE_PASSWORD")
                keyAlias = signingCreds.getValue("KEY_ALIAS")
                keyPassword = signingCreds.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
