/*
 * Per-OS/arch JNI artifact: ships libmpv_jni as a classpath resource at
 * /cn/enaium/mpv/native/<classifier>/ for NativeLoader.
 *
 * The module name encodes the classifier (jni-jvm-darwin-aarch64), so this
 * file is identical across the five per-host modules.
 */
import org.gradle.internal.os.OperatingSystem

plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

val classifier = project.name.removePrefix("jni-jvm-")
val jniOs = classifier.substringBefore('-')
val jniArch = classifier.substringAfter('-')

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

fun resolveCmakeExecutable(): String {
    val exeName = if (OperatingSystem.current().isWindows) "cmake.exe" else "cmake"
    System.getenv("PATH")?.split(File.pathSeparator).orEmpty().forEach { dir ->
        val candidate = File(dir, exeName)
        if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
    }
    listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/opt/local/bin").forEach { dir ->
        val candidate = File(dir, exeName)
        if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
    }
    return exeName
}

val cmakeExecutable: String by lazy { resolveCmakeExecutable() }

val hostOs = OperatingSystem.current()
val hostArch = System.getProperty("os.arch").lowercase()

// Each artifact is built on its matching runner. macOS cross-builds both
// slices with Xcode; the JNI bridge has no libmpv link dependency, so no
// target libmpv is needed at build time.
val canBuildHere = when (jniOs) {
    "darwin" -> hostOs.isMacOsX
    "linux" -> hostOs.isLinux && when (jniArch) {
        "x86_64" -> !hostArch.contains("aarch64") && !hostArch.contains("arm64")
        "aarch64" -> hostArch.contains("aarch64") || hostArch.contains("arm64")
        else -> false
    }

    "windows" -> hostOs.isWindows && jniArch == "x86_64"
    else -> false
}

val libFile = when (jniOs) {
    "windows" -> "mpv_jni.dll"
    "darwin" -> "libmpv_jni.dylib"
    else -> "libmpv_jni.so"
}

val resourceDir = "cn/enaium/mpv/native/$classifier"
val nativeOutputDir = layout.buildDirectory.dir("jni-native/$classifier")
val cmakeBuildDir = layout.buildDirectory.dir("cmake-jni/$classifier")

val configureJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "cmake-configures libmpv_jni for $classifier."
    onlyIf { canBuildHere }
    val outDir = nativeOutputDir.get().asFile
    val buildDir = cmakeBuildDir.get().asFile
    doFirst {
        outDir.mkdirs()
        buildDir.mkdirs()
    }
    workingDir = buildDir
    val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME") ?: ""
    val jniPlatform = when (jniOs) {
        "darwin" -> "darwin"
        "windows" -> "win32"
        else -> "linux"
    }
    val archFlags = when {
        jniOs == "darwin" && jniArch == "aarch64" ->
            listOf("-DCMAKE_OSX_ARCHITECTURES=arm64", "-DCMAKE_SYSTEM_PROCESSOR=arm64")

        jniOs == "darwin" ->
            listOf("-DCMAKE_OSX_ARCHITECTURES=x86_64", "-DCMAKE_SYSTEM_PROCESSOR=x86_64")

        else -> emptyList()
    }
    commandLine(
        listOf(
            cmakeExecutable,
            rootProject.file("jni").absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DMPV_SOURCE_DIR=${rootProject.file("mpv").absolutePath}",
            "-DJNI_INCLUDE_DIR=$javaHome/include",
            "-DJNI_INCLUDE_DIR_PLATFORM=$javaHome/include/$jniPlatform",
            "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outDir.absolutePath}",
        ) + archFlags +
            if (jniOs == "darwin") {
                listOf("-DCMAKE_OSX_DEPLOYMENT_TARGET=12.0")
            } else if (jniOs == "windows") {
                // Visual Studio is a multi-config generator; DLLs land in
                // Release/ unless the RUNTIME output directory is set.
                listOf(
                    "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${outDir.absolutePath}",
                    "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY_RELEASE=${outDir.absolutePath}",
                )
            } else {
                emptyList()
            },
    )
}

val buildJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds $libFile for $classifier."
    onlyIf { canBuildHere }
    dependsOn(configureJniLibrary)
    workingDir = cmakeBuildDir.get().asFile
    commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
    inputs.file(rootProject.file("jni/CMakeLists.txt"))
    inputs.file(rootProject.file("jni/mpv_jni.cpp"))
    inputs.dir(rootProject.file("mpv/include"))
    outputs.file(nativeOutputDir.map { it.file(libFile) })
}

tasks.named<Copy>("processResources") {
    dependsOn(buildJniLibrary)
    from(buildJniLibrary.map { it.outputs.files }) {
        include(libFile)
        into(resourceDir)
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates(
        groupId = rootProject.group.toString(),
        artifactId = "mpv-kmp-jni-jvm-$classifier",
        version = rootProject.version.toString(),
    )
    pom {
        name.set("mpv-kmp-jni-jvm-$classifier")
        description.set(
            "Prebuilt JNI bridge (libmpv_jni) for mpv-kmp on $jniOs/$jniArch. " +
                "Loaded automatically by NativeLoader; libmpv is resolved at runtime.",
        )
        url.set("https://github.com/Enaium/mpv-kmp")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("GPL-2.0-or-later")
                url.set("https://www.gnu.org/licenses/gpl-2.0.html")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("Enaium")
            }
        }
        scm {
            url.set("https://github.com/Enaium/mpv-kmp")
            connection.set("scm:git:git@github.com:Enaium/mpv-kmp.git")
            developerConnection.set("scm:git:git@github.com:Enaium/mpv-kmp.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Enaium/mpv-kmp/issues")
        }
    }
}
