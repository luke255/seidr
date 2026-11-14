import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Version and update source live in gradle.properties. versionCode follows the
// version (1.2.3 -> 10203), so minor and patch stay below 100.
val appVersion = providers.gradleProperty("seidr.version").get()
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "seidr.version: minor and patch must be below 100" }
    major * 10_000 + minor * 100 + patch
}
val githubRepo = providers.gradleProperty("seidr.repo").get()

// Release signing: a keystore outside the repo, in ~/.config/seidr/. Without it,
// release builds come out unsigned and don't self-update (another key's updates
// wouldn't install anyway). Losing it means every installed copy must be
// uninstalled before a new build will install, so back it up.
val home: String = System.getProperty("user.home")
val keystoreFile = file("$home/.config/seidr/release.jks")
val signingProps = Properties().apply {
    val f = file("$home/.config/seidr/signing.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val canSign = keystoreFile.isFile

android {
    namespace = "eu.bradshaw.seidr"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "eu.bradshaw.seidr"
        minSdk = 34
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
        // The latest release's version.json (see the dist task and scripts/release.sh).
        buildConfigField("String", "UPDATE_URL",
            if (canSign) "\"https://github.com/$githubRepo/releases/latest/download/version.json\"" else "\"\"")
        // Phones and 64-bit TVs, plus 32-bit-userspace TVs.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared") }
        }
    }

    // MilkDrop: libprojectM (vendored in src/main/cpp/projectm) plus a JNI shim.
    // Ninja keeps it incremental: once built, it only recompiles when the C++ changes.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    if (canSign) signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = "seidr"
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            // No self-update: the published APK is the release build, another package.
            buildConfigField("String", "UPDATE_URL", "\"\"")
            resValue("string", "app_name", "Seiðr (debug)")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
        prefab = true // the OpenXR loader's headers and library, for CMake
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// --- dist: build/dist/{seidr-<version>.apk, version.json} -------------------------
//
// What scripts/release.sh uploads to a GitHub release. version.json names the
// APK by its tagged URL, not "latest", so a release published mid-download
// can't swap the file under the checksum.

abstract class DistTask : DefaultTask() {
    @get:InputFiles abstract val apkDir: DirectoryProperty
    @get:Internal abstract val loader: Property<BuiltArtifactsLoader>
    @get:Input abstract val applicationId: Property<String>
    @get:Input abstract val repo: Property<String>
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val signed: Property<Boolean>
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction
    fun run() {
        if (!signed.get()) throw GradleException("No release keystore at ~/.config/seidr/release.jks: dist needs a signed build")
        val out = outDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val output = (loader.get().load(apkDir.get()) ?: throw GradleException("no APK built")).elements.single()
        val version = output.versionName
        val name = "seidr-$version.apk"
        val dest = File(out, name)
        File(output.outputFile).copyTo(dest, overwrite = true)
        val sha = MessageDigest.getInstance("SHA-256").digest(dest.readBytes()).joinToString("") { "%02x".format(it) }
        File(out, "version.json").writeText(
            """
            {
              "applicationId": "${applicationId.get()}",
              "versionCode": ${output.versionCode},
              "versionName": "$version",
              "apk": "https://github.com/${repo.get()}/releases/download/v$version/$name",
              "sha256": "$sha",
              "size": ${dest.length()},
              "minSdk": ${minSdk.get()}
            }
            """.trimIndent() + "\n"
        )
        logger.lifecycle("+ seidr $version (${output.versionCode}) → $out")
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        tasks.register<DistTask>("dist") {
            group = "distribution"
            description = "Build the signed release APK and version.json into build/dist/"
            apkDir.set(variant.artifacts.get(SingleArtifact.APK))
            loader.set(variant.artifacts.getBuiltArtifactsLoader())
            applicationId.set(variant.applicationId)
            repo.set(githubRepo)
            minSdk.set(34)
            signed.set(canSign)
            outDir.set(rootProject.layout.buildDirectory.dir("dist"))
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3.adaptive:adaptive:1.3.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.3.0")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.work:work-runtime-ktx:2.12.0")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    // The headset visualiser: the Khronos OpenXR loader (finds the Quest's runtime).
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.65")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
