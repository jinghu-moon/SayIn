import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.android.library) }

val benchmarkBackend = providers.gradleProperty("sayin.r2t2BenchmarkBackend").orNull
if (benchmarkBackend != null) {
    require(benchmarkBackend in setOf("arm", "vulkan") &&
        providers.gradleProperty("sayin.instrumentationBuildType").orNull == "imageTest" &&
        providers.gradleProperty("sayin.emulatorTests").orNull != "true") {
        "R2T2 benchmark backends require an arm64 imageTest build and backend arm or vulkan."
    }
}

android {
    namespace = "seeyuer.sayin.translation"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += if (providers.gradleProperty("sayin.emulatorTests").orNull == "true") "x86_64" else "arm64-v8a" }
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild.cmake {
            // Quantized CPU kernels need optimization in Debug too; retain symbols for JNI debugging.
            arguments += listOf(
                "-DANDROID_STL=c++_shared",
                "-DCMAKE_C_FLAGS_DEBUG=-O2 -g",
                "-DCMAKE_CXX_FLAGS_DEBUG=-O2 -g",
                "-DSAYIN_R2T2_BENCHMARK_BACKEND=${benchmarkBackend.orEmpty()}",
            )
            if (benchmarkBackend == "vulkan") {
                providers.gradleProperty("sayin.r2t2VulkanHeaders").orNull?.let { arguments += "-DVulkan_INCLUDE_DIR=$it" }
                providers.gradleProperty("sayin.r2t2SpirvHeaders").orNull?.let { arguments += "-DSPIRV-Headers_DIR=$it" }
                providers.gradleProperty("sayin.r2t2Glslc").orNull?.let { arguments += "-DVulkan_GLSLC_EXECUTABLE=$it" }
            }
            cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
        }
    }
    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
val verifyTranslationSource = tasks.register<Exec>("verifyTranslationSource") {
    val root = rootProject.projectDir.parentFile
    workingDir(root)
    commandLine("python", root.resolve("scripts/prepare_translation.py"), "--verify-source")
}
tasks.named("preBuild") { dependsOn(verifyTranslationSource) }
abstract class CopyTranslationCatalog : DefaultTask() {
    @get:InputFile abstract val manifest: RegularFileProperty
    @get:InputDirectory abstract val notices: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun copyManifest() {
        val target = outputDirectory.get().asFile.resolve("translation-artifacts.json")
        target.parentFile.mkdirs()
        manifest.get().asFile.copyTo(target, overwrite = true)
        val licenseDirectory = target.parentFile.resolve("licenses/translation").apply { mkdirs() }
        listOf("THIRD-PARTY-NOTICES.txt", "llama.cpp-MIT.txt", "Hy-MT2-Apache-2.0.txt", "PLaMo-Community-License.txt", "Android-NDK-29-toolchain-NOTICE.txt").forEach {
            notices.get().asFile.resolve(it).copyTo(licenseDirectory.resolve(it), overwrite = true)
        }
    }
}
val copyCatalog = tasks.register<CopyTranslationCatalog>("copyTranslationCatalog") {
    manifest.set(rootProject.layout.projectDirectory.file("translation-artifacts.json"))
    notices.set(rootProject.layout.projectDirectory.dir("../docs/licenses/translation"))
    outputDirectory.set(layout.buildDirectory.dir("generated/catalog"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(copyCatalog) { it.outputDirectory }
}
