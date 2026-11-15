import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "seeyuer.sayin"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "seeyuer.sayin"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { compose = true }
    sourceSets["main"].assets.srcDir(rootProject.file("local-asr/assets"))
    androidResources {
        noCompress += "onnx"
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
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
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
    implementation(libs.okhttp)
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
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.tooling.preview)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    debugImplementation(libs.compose.tooling)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

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
    outputDirectory.set(layout.buildDirectory.dir("generated/pairingJni"))
    commandLine("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
        projectRoot.resolve("scripts/build_pairing_android.ps1").absolutePath)
}
androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildPairingNative) { it.outputDirectory }
}
