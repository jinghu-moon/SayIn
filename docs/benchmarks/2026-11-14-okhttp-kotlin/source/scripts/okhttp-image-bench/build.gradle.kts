plugins { kotlin("jvm") version "2.4.21" }
repositories { mavenCentral() }
val sdkRoot = providers.gradleProperty("sdkRoot").getOrElse("C:/Users/seeyuer/AppData/Local/Android/Sdk")
dependencies {
    implementation("com.squareup.okhttp3:okhttp-jvm:5.5.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    compileOnly(files("$sdkRoot/platforms/android-37.0/android.jar"))
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17); allWarningsAsErrors.set(true) } }
dependencyLocking { lockAllConfigurations() }
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
tasks.register<JavaExec>("dex") {
    dependsOn(tasks.jar)
    classpath = files("$sdkRoot/build-tools/37.0.0/lib/d8.jar")
    mainClass.set("com.android.tools.r8.D8")
    val output = layout.buildDirectory.file("bench-dex.zip")
    inputs.files(configurations.runtimeClasspath, tasks.jar.flatMap { it.archiveFile })
    outputs.file(output)
    doFirst {
        args = listOf("--release", "--min-api", "26", "--lib", "$sdkRoot/platforms/android-37.0/android.jar", "--output", output.get().asFile.absolutePath,
            tasks.jar.get().archiveFile.get().asFile.absolutePath) + configurations.runtimeClasspath.get().files.map { it.absolutePath }
    }
}
