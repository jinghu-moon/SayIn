import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipFile

plugins { kotlin("jvm") version "2.4.21" }
repositories { mavenCentral() }
val sdkRoot = providers.gradleProperty("sdkRoot").getOrElse("C:/Users/seeyuer/AppData/Local/Android/Sdk")
dependencies {
    implementation("com.squareup.okhttp3:okhttp-jvm:5.5.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    implementation("tech.kwik:kwik:0.12.2")
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
    doLast {
        // D8 emits dex only. Retain SPI descriptors used by Agent15's key exchange factory.
        val services = linkedMapOf<String, MutableSet<String>>()
        configurations.runtimeClasspath.get().files.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }.forEach { entry ->
                    services.getOrPut(entry.name) { linkedSetOf() }.addAll(zip.getInputStream(entry).bufferedReader().readLines())
                }
            }
        }
        FileSystems.newFileSystem(output.get().asFile.toPath(), emptyMap<String, Any>()).use { zip ->
            services.forEach { (name, lines) ->
                val path = zip.getPath("/$name")
                Files.createDirectories(path.parent)
                Files.writeString(path, lines.joinToString("\n", postfix = "\n"))
            }
        }
    }
}
