import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries)
}

// Opt-in emulator ABI is limited to the isolated instrumentation variant.
val emulatorTests = providers.gradleProperty("sayin.emulatorTests").orNull == "true"
if (emulatorTests) {
    require(providers.gradleProperty("sayin.instrumentationBuildType").orNull == "imageTest")
}

val repositoryRoot = rootProject.projectDir.parentFile
val translationManifestFile = File(rootProject.projectDir, "translation-artifacts.json")

data class BundledTranslationModel(val file: String, val bytes: Long, val sha256: String)

val bundledTranslationModels: List<BundledTranslationModel> = run {
    val root = JsonSlurper().parse(translationManifestFile) as Map<*, *>
    (root["models"] as List<*>).map { it as Map<*, *> }
        .filter { it["bundled"] == true }
        .map {
            BundledTranslationModel(
                it["file"] as String,
                (it["bytes"] as Number).toLong(),
                it["sha256"] as String,
            )
        }
}

/**
 * Copies only models marked `bundled` in the pinned manifest. The file is stored
 * uncompressed in the APK so the runtime can open its asset descriptor directly.
 */
abstract class BundleTranslationModels : DefaultTask() {
    @get:InputFile abstract val manifest: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val modelFiles: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun bundle() {
        val root = JsonSlurper().parse(manifest.get().asFile) as Map<*, *>
        val models = (root["models"] as List<*>).map { it as Map<*, *> }.filter { it["bundled"] == true }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val target = File(output, "translation").apply { mkdirs() }
        for (entry in models) {
            val name = entry["file"] as String
            val expectedBytes = (entry["bytes"] as Number).toLong()
            val expectedSha = entry["sha256"] as String
            val source = modelFiles.files.singleOrNull { it.name == name }
                ?: error("Bundled translation model file is missing: $name")
            check(source.isFile) { "Bundled translation model file is missing: ${source.absolutePath}" }
            check(source.length() == expectedBytes) { "Bundled translation model size mismatch: $name" }
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().buffered(1 shl 20).use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expectedSha) {
                "Bundled translation model SHA-256 mismatch: $name"
            }
            source.copyTo(File(target, name), overwrite = true)
        }
    }
}

android {
    namespace = "seeyuer.sayin"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "seeyuer.sayin"
        // The pinned LAN transport requires platform TLS 1.3 (Android 10+).
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += if (emulatorTests) "x86_64" else "arm64-v8a" }
    }

    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].assets.srcDir(rootProject.file("local-asr/assets"))
    androidResources {
        noCompress += "onnx"
        // Bundled translation weights must be directly openable as an asset descriptor.
        noCompress += "gguf"
        // Explicit locale_config.xml prevents dependency translations advertising extra languages.
        // Include script-qualified Chinese and legacy zh-CN library resources.
        // Pseudo locales are generated only in debug, not advertised as product languages.
        localeFilters += listOf("en", "b+zh+Hans", "zh-rCN", "en-rXA", "ar-rXB")
    }
    lint {
        fatal += setOf("MissingTranslation", "ExtraTranslation", "StringFormatInvalid", "StringFormatMatches")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testBuildType = providers.gradleProperty("sayin.instrumentationBuildType").getOrElse("debug")
    buildTypes {
        debug { isPseudoLocalesEnabled = true }
        create("imageTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".imagetest"
            versionNameSuffix = "-image-test"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (providers.gradleProperty("sayinVerification").orNull == "true") {
                applicationIdSuffix = ".translationverification"
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":translation"))
    // Reproducible download + model preparation: python scripts/prepare_x_asr.py
    implementation(files(rootProject.file("local-asr/sherpa-onnx-2.0.1.aar")))
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.json.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
    add("imageTestImplementation", libs.compose.ui.test.manifest)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    androidTestCompileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.aboutlibraries.compose.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.tooling.preview)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    debugImplementation(libs.compose.tooling)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

val bundleTranslationModels = tasks.register<BundleTranslationModels>("bundleTranslationModels") {
    manifest.set(translationManifestFile)
    modelFiles.from(bundledTranslationModels.map { File(repositoryRoot, "assets/translation-model/${it.file}") })
    outputDirectory.set(layout.buildDirectory.dir("generated/translationAssets"))
}

val verifyLocalAsr = tasks.register<Exec>("verifyLocalAsr") {
    val projectRoot = rootProject.projectDir.parentFile
    workingDir(projectRoot)
    commandLine("python", projectRoot.resolve("scripts/prepare_x_asr.py").absolutePath, "--verify-only")
}
tasks.named("preBuild") { dependsOn(verifyLocalAsr) }

abstract class BuildPairingNative : Exec() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
}

val buildPairingNative = tasks.register<BuildPairingNative>("buildPairingNative") {
    val projectRoot = rootProject.projectDir.parentFile
    inputs.dir(projectRoot.resolve("desktop/crates/pairing/src"))
    inputs.file(projectRoot.resolve("desktop/crates/pairing/Cargo.toml"))
    inputs.file(projectRoot.resolve("desktop/Cargo.lock"))
    inputs.file(projectRoot.resolve("scripts/build_pairing_android.ps1"))
    inputs.property("abi", if (emulatorTests) "x86_64" else "arm64-v8a")
    outputDirectory.set(layout.buildDirectory.dir("generated/pairingJni"))
    commandLine("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
        projectRoot.resolve("scripts/build_pairing_android.ps1").absolutePath,
        "-Abi", if (emulatorTests) "x86_64" else "arm64-v8a")
}
androidComponents.beforeVariants { variant ->
    if (emulatorTests && variant.buildType != "imageTest") variant.enable = false
}
androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildPairingNative) { it.outputDirectory }
    variant.sources.assets?.addGeneratedSourceDirectory(bundleTranslationModels) { it.outputDirectory }
}
