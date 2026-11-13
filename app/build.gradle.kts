plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: {release.keystore, credentials.env}, looked for in the project root
// first, then the canonical store at ~/sandbox/.signing/geo-trail-maps. Override with
// -PgeoTrailMaps.signingDir=... or GEO_TRAIL_MAPS_SIGNING_DIR. Without a key, release
// builds fail rather than produce an APK that can't update an existing install.
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
        minSdk = 33
        targetSdk = 37
        versionCode = 24
        versionName = "0.9.7"
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
            signingConfig = signingConfigs.findByName("release")
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
        aidl = true
    }
}

// Fail release builds (not debug ones) when no release key was found.
val hasReleaseKey = signingCreds.isNotEmpty()
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        if (!hasReleaseKey) throw GradleException(
            "No release signing key: credentials.env not found via -PgeoTrailMaps.signingDir, " +
                "GEO_TRAIL_MAPS_SIGNING_DIR, the project root or ~/sandbox/.signing/geo-trail-maps"
        )
    }
}

// The license and notices ship inside the APK as assets under legal/, shown in
// Settings → Open-source licenses, so installs without the license ZIP still get them.
abstract class CopyLegalNotices : DefaultTask() {
    @get:InputFiles abstract val notices: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().dir("legal").asFile.apply { deleteRecursively(); mkdirs() }
        notices.forEach { it.copyTo(File(dir, it.name)) }
    }
}

val copyLegalNotices = tasks.register<CopyLegalNotices>("copyLegalNotices") {
    notices.from(rootProject.files("LICENSE", "THIRD_PARTY_NOTICES.md", "licenses/Apache-2.0.txt"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLegalNotices, CopyLegalNotices::outputDir)
    }
}

// Release builds fail if a runtime dependency isn't covered by the Libraries table in
// THIRD_PARTY_NOTICES.md. None of the covered artifacts ship their own NOTICE file (as of 0.8.2).
val noticedModules = listOf(
    "androidx.", "org.jetbrains.kotlin:", "org.jetbrains.kotlinx:", "org.jetbrains:annotations",
    "com.google.guava:listenablefuture", "org.jspecify:jspecify",
)
val checkLegalNotices = tasks.register("checkLegalNotices") {
    val modules = configurations.named("releaseRuntimeClasspath").flatMap { config ->
        config.incoming.resolutionResult.rootComponent.map { root ->
            val seen = mutableSetOf<ResolvedComponentResult>()
            fun walk(c: ResolvedComponentResult) {
                if (seen.add(c)) c.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { walk(it.selected) }
            }
            walk(root)
            seen.mapNotNull { (it.id as? ModuleComponentIdentifier)?.let { id -> "${id.group}:${id.module}" } }
        }
    }
    inputs.property("modules", modules)
    doLast {
        val missing = modules.get().filter { m -> noticedModules.none { m.startsWith(it) } }
        if (missing.isNotEmpty()) throw GradleException(
            "Runtime dependencies not covered by THIRD_PARTY_NOTICES.md (update it and noticedModules): $missing"
        )
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkLegalNotices) }

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
