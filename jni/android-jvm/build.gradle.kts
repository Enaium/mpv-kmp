/*
 * Android library packaging the per-ABI libmpv_jni bridge for the four
 * Android ABIs. libmpv itself is not bundled (its FFmpeg/libass/libplacebo
 * stack must match the application's licensing and size constraints); point
 * MPV_ANDROID_LIBS_DIR at a directory containing <abi>/libmpv.so to include
 * it, and NativeLoader will dlopen it from the APK's native library directory.
 */
import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

// ---------------------------------------------------------------------------
// Toolchain discovery
// ---------------------------------------------------------------------------

fun localSdkDir(): String? {
    val f = rootProject.file("local.properties")
    if (!f.isFile) return null
    return f.readLines()
        .firstOrNull { it.trimStart().startsWith("sdk.dir=") }
        ?.substringAfter('=')
        ?.trim()
}

fun androidNdkPath(): String? {
    val home = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: localSdkDir()
        ?: System.getProperty("user.home") + "/Android/Sdk"
    val ndkDir = File(home, "ndk")
    if (!ndkDir.isDirectory) return null
    return ndkDir.listFiles()
        ?.filter { it.isDirectory && it.name.matches(Regex("\\d+\\.\\d+\\.\\d+.*")) }
        ?.sortedBy { it.name }
        ?.lastOrNull()
        ?.absolutePath
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

fun hostPrebuiltDir(): String {
    val os = OperatingSystem.current()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.isMacOsX && arch.contains("aarch64") -> "darwin-arm64"
        os.isMacOsX -> "darwin-x86_64"
        os.isLinux && arch.contains("aarch64") -> "linux-aarch64"
        os.isLinux -> "linux-x86_64"
        os.isWindows -> "windows-x86_64"
        else -> error("Unsupported host for android-jvm build: $os/$arch")
    }
}

fun resolveNinjaExecutable(ndk: String): String {
    listOf(
        "$ndk/prebuilt/${hostPrebuiltDir()}/bin/ninja",
        "$ndk/build/tools/ninja",
    ).forEach { candidate ->
        val f = File(candidate)
        if (f.isFile && f.canExecute()) return candidate
    }
    return "ninja"
}

val cmakeExecutable: String by lazy { resolveCmakeExecutable() }
val ndkPath: String? = androidNdkPath()
val ninjaExecutable: String by lazy { ndkPath?.let { resolveNinjaExecutable(it) } ?: "ninja" }

// All four NDK ABIs (API 24+, matching the androidNative targets of :mpv-kmp).
val androidAbis = mapOf(
    "androidNativeArm64" to "arm64-v8a",
    "androidNativeArm32" to "armeabi-v7a",
    "androidNativeX64" to "x86_64",
    "androidNativeX86" to "x86",
)

// ---------------------------------------------------------------------------
// Android library
// ---------------------------------------------------------------------------

android {
    namespace = "cn.enaium.mpv.android"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
    }
}

// ---------------------------------------------------------------------------
// Native: build libmpv_jni.so for every ABI with the NDK.
// ---------------------------------------------------------------------------

val jniSourceDir = rootProject.file("jni").absolutePath
val generatedJniLibs = layout.buildDirectory.dir("generated/jniLibs")

// Optional prebuilt libmpv.so tree, laid out as <abi>/libmpv.so.
val prebuiltLibmpvDir: File? =
    (System.getenv("MPV_ANDROID_LIBS_DIR")
        ?: providers.gradleProperty("mpv.android.libs.dir").orNull)
        ?.let(::File)
        ?.takeIf { it.isDirectory }

abstract class PrepareJniLibsTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val abis: MapProperty<String, String>

    @get:Input
    abstract val cmakeBuildRoot: Property<String>

    @get:Optional
    @get:InputDirectory
    abstract val externalLibmpvDir: DirectoryProperty

    @TaskAction
    fun run() {
        val root = File(cmakeBuildRoot.get())
        outputDir.get().asFile.deleteRecursively()
        abis.get().forEach { (_, abi) ->
            val src = File(root, "$abi/libmpv_jni.so")
            if (!src.isFile) {
                throw GradleException("Missing JNI bridge for $abi: $src")
            }
            val dstDir = File(outputDir.get().asFile, abi)
            dstDir.mkdirs()
            src.copyTo(File(dstDir, "libmpv_jni.so"), overwrite = true)
            externalLibmpvDir.orNull?.let { directory ->
                val external = directory.asFile
                val libmpv = File(external, "$abi/libmpv.so")
                if (libmpv.isFile) {
                    libmpv.copyTo(File(dstDir, "libmpv.so"), overwrite = true)
                }
            }
        }
    }
}

ndkPath?.let { ndk ->
    val toolchain = "$ndk/build/cmake/android.toolchain.cmake"

    androidAbis.forEach { (_, abi) ->
        val buildDir = layout.buildDirectory.dir("cmake-jni/$abi")

        val configureTask = tasks.register<Exec>("configureJniLib_$abi") {
            doFirst {
                buildDir.get().asFile.mkdirs()
            }
            workingDir = buildDir.get().asFile
            commandLine(
                cmakeExecutable,
                "-G", "Ninja",
                "-DCMAKE_MAKE_PROGRAM=$ninjaExecutable",
                jniSourceDir,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
                "-DANDROID_ABI=$abi",
                "-DANDROID_PLATFORM=android-24",
                "-DANDROID_STL=c++_static",
                "-DANDROID_JNI=ON",
                "-DMPV_SOURCE_DIR=${rootProject.file("mpv").absolutePath}",
                "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${buildDir.get().asFile.absolutePath}",
                "-UJNI_INCLUDE_DIR",
                "-UJNI_INCLUDE_DIR_PLATFORM",
            )
        }

        tasks.register<Exec>("buildJniLib_$abi") {
            dependsOn(configureTask)
            workingDir = buildDir.get().asFile
            commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
            inputs.files(
                rootProject.file("jni/CMakeLists.txt"),
                rootProject.file("jni/mpv_jni.cpp"),
            )
            inputs.dir(rootProject.file("mpv/include"))
            outputs.file(buildDir.map { it.file("libmpv_jni.so") })
        }
    }
}

val prepareJniLibs = tasks.register<PrepareJniLibsTask>("prepareJniLibs") {
    outputDir.set(generatedJniLibs)
    abis.set(androidAbis)
    cmakeBuildRoot.set(layout.buildDirectory.dir("cmake-jni").map { it.asFile.absolutePath })
    externalLibmpvDir.set(prebuiltLibmpvDir)
}

prepareJniLibs.configure {
    androidAbis.values.forEach { abi ->
        dependsOn("buildJniLib_$abi")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareJniLibs) { it.outputDir }
    }
}

// ---------------------------------------------------------------------------
// Publishing
// ---------------------------------------------------------------------------

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates(
        groupId = rootProject.group.toString(),
        artifactId = "mpv-kmp-android-jvm",
        version = rootProject.version.toString(),
    )
    pom {
        name.set("mpv-kmp-android-jvm")
        description.set(
            "Android AAR with the per-ABI libmpv_jni bridge used by mpv-kmp's Android target. " +
                "Provide libmpv.so per ABI via MPV_ANDROID_LIBS_DIR or the app's own jniLibs.",
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
