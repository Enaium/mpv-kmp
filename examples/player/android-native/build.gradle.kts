plugins {
    alias(libs.plugins.android.application)
}

// Builds the Android native player APK from the Kotlin/Native libmain.so
// produced by :examples:player. libmpv.so must be supplied per ABI through
// MPV_ANDROID_LIBS_DIR (the native link needs it and the APK runs it at
// runtime); without it the link/assemble task fails with a clear message.
val androidAbis = mapOf(
    "androidNativeArm64" to "arm64-v8a",
    "androidNativeArm32" to "armeabi-v7a",
    "androidNativeX64" to "x86_64",
    "androidNativeX86" to "x86",
)
val cxxSharedTriple = mapOf(
    "androidNativeArm64" to "aarch64-linux-android",
    "androidNativeArm32" to "arm-linux-androideabi",
    "androidNativeX64" to "x86_64-linux-android",
    "androidNativeX86" to "i686-linux-android",
)

val providedMpvLibs = providers.environmentVariable("MPV_ANDROID_LIBS_DIR").orNull
    ?: providers.gradleProperty("mpv.android.libs.dir").orNull
val mpvLibsRoot = providedMpvLibs?.let(::File)
    ?: rootProject.layout.buildDirectory.dir("mpv-android-libs").get().asFile
val fetchAndroidMpvLibs = if (providedMpvLibs == null) {
    rootProject.tasks.named("fetchAndroidMpvLibs")
} else {
    null
}

fun libcxxShared(target: String): File? {
    val konanData = System.getenv("KONAN_DATA_DIR")
        ?: "${System.getProperty("user.home")}/.konan"
    val toolchain = File(konanData, "dependencies").listFiles()
        ?.firstOrNull { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
        ?: return null
    val triple = cxxSharedTriple[target] ?: return null
    return File(toolchain, "sysroot/usr/lib/$triple/libc++_shared.so").takeIf { it.exists() }
}

abstract class PrepareJniLibsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val abis: MapProperty<String, String>

    @get:Input
    abstract val buildType: Property<String>

    @get:Input
    abstract val cxxShared: MapProperty<String, String>

    @get:Input
    abstract val mpvLibsDir: Property<String>

    @TaskAction
    fun run() {
        val bin = project.layout.projectDirectory.dir("../build/bin").asFile
        val suffix = buildType.get().replaceFirstChar { it.uppercase() }
        val mpvDir = mpvLibsDir.orNull
        outputDir.get().asFile.deleteRecursively()
        abis.get().forEach { (target, abi) ->
            val src = File(bin, "$target/main${suffix}Shared/libmain.so")
            if (!src.isFile) {
                throw GradleException(
                    "Missing $src. Link :examples:player for $target first " +
                        "(MPV_ANDROID_LIBS_DIR must contain $abi/libmpv.so).",
                )
            }
            val dst = File(outputDir.get().asFile, abi)
            dst.mkdirs()
            src.copyTo(File(dst, "libmain.so"), overwrite = true)
            cxxShared.orNull?.get(target)?.let { path ->
                File(path).takeIf { it.isFile }?.copyTo(File(dst, "libc++_shared.so"), overwrite = true)
            }
            if (mpvDir != null) {
                File(mpvDir, abi).listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".so") }
                    ?.forEach { it.copyTo(File(dst, it.name), overwrite = true) }
            } else {
                logger.warn("MPV_ANDROID_LIBS_DIR is not set; libmpv.so and its dependencies will be missing from the APK.")
            }
        }
    }
}

android {
    namespace = "cn.enaium.mpv.example.player.ndk"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.enaium.mpv.example.player.ndk"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDir("../src/commonMain/resources")
}

androidComponents {
    onVariants { variant ->
        val variantBuildType = requireNotNull(variant.buildType) { "application variant has no build type" }
        val capitalized = variantBuildType.replaceFirstChar { it.uppercase() }
        val prepare = tasks.register<PrepareJniLibsTask>("prepareJniLibs$capitalized") {
            outputDir.set(layout.buildDirectory.dir("generated/jniLibs/\$variantBuildType"))
            abis.set(androidAbis)
            buildType.set(variantBuildType)
            cxxShared.set(androidAbis.keys.mapNotNull { target ->
                libcxxShared(target)?.let { target to it.absolutePath }
            }.toMap())
            mpvLibsDir.set(mpvLibsRoot.absolutePath)
        }
        androidAbis.keys.forEach { target ->
            val linkTask = project(":examples:player").tasks.named(
                "linkMain${capitalized}Shared${target.replaceFirstChar { it.uppercase() }}",
            )
            prepare.configure {
                dependsOn(linkTask)
                inputs.files(linkTask.flatMap { (it as org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink).outputFile })
            }
            fetchAndroidMpvLibs?.let { linkTask.configure { dependsOn(it) } }
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepare) { it.outputDir }
    }
}

dependencies {
    implementation(libs.sdl.kmp.android.jvm)
}
