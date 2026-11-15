import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { alias(libs.plugins.android.application) }
android {
    namespace = "seeyuer.sayin.translationbench"
    compileSdk = 37
    testBuildType = providers.gradleProperty("translationBenchmarkBuildType").getOrElse("debug")
    defaultConfig {
        applicationId = "seeyuer.sayin.translationbench"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "benchmark-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":translation"))
    implementation(libs.coroutines.android)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    // Static analysis annotations belong on the compiler/library classpath, not in Android runtime dex.
    androidTestCompileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
}
