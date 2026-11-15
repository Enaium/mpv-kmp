@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// Kotlin/Native's Android sysroot ships the NDK stub libraries SDL3's
// android drivers reference; add them to the link line.
fun konanAndroidLibDir(abi: String): String? {
    val konanData = System.getenv("KONAN_DATA_DIR")
        ?: "${System.getProperty("user.home")}/.konan"
    val toolchain = File(konanData, "dependencies").listFiles()
        ?.firstOrNull { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
        ?: return null
    val triple = when (abi) {
        "arm64-v8a" -> "aarch64-linux-android"
        "armeabi-v7a" -> "arm-linux-androideabi"
        "x86_64" -> "x86_64-linux-android"
        "x86" -> "i686-linux-android"
        else -> return null
    }
    return "$toolchain/sysroot/usr/lib/$triple/26"
}

val hostOsName = System.getProperty("os.name").orEmpty().lowercase()
val defaultMpvLibraryDir: String? = when {
    hostOsName.contains("mac") -> listOf("/opt/homebrew/lib", "/usr/local/lib")
        .firstOrNull { File(it, "libmpv.dylib").isFile }

    hostOsName.contains("linux") -> listOf(
        "/usr/lib/x86_64-linux-gnu",
        "/usr/lib/aarch64-linux-gnu",
        "/usr/lib64",
        "/usr/lib",
    ).firstOrNull { File(it, "libmpv.so").isFile }

    else -> null
}

val mpvLibraryDir: String? = providers.gradleProperty("mpv.library.dir").orNull
    ?: providers.environmentVariable("MPV_LIBRARY_DIR").orNull
    ?: defaultMpvLibraryDir
val mpvAndroidLibsOverride: String? = providers.environmentVariable("MPV_ANDROID_LIBS_DIR").orNull
    ?: providers.gradleProperty("mpv.android.libs.dir").orNull
val mpvAndroidLibsRoot: File = mpvAndroidLibsOverride?.let(::File)
    ?: rootProject.layout.buildDirectory.dir("mpv-android-libs").get().asFile
val usesFetchedMpvAndroidLibs = mpvAndroidLibsOverride == null

/**
 * Complete player demo: SDL3 + Dear ImGui controls on JVM/native/Android
 * (with libmpv's software renderer feeding an SDL texture), and the
 * HTMLMediaElement backend in the browser.
 */
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        mainRun {
            mainClass = "cn.enaium.mpv.example.player.MainKt"
        }
    }

    android {
        namespace = "cn.enaium.mpv.example.player"
        compileSdk = 37
        minSdk = 24

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    macosArm64 { binaries.executable() }
    macosX64 { binaries.executable() }
    linuxX64 { binaries.executable() }
    linuxArm64 { binaries.executable() }
    mingwX64 { binaries.executable() }

    androidNativeArm64 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("arm64-v8a")?.let { linkerOpts("-L$it") }
            linkerOpts("-L${mpvAndroidLibsRoot.resolve("arm64-v8a")}")
            linkerOpts("-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384")
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeArm32 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("armeabi-v7a")?.let { linkerOpts("-L$it") }
            linkerOpts("-L${mpvAndroidLibsRoot.resolve("armeabi-v7a")}")
            linkerOpts("-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384")
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeX64 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("x86_64")?.let { linkerOpts("-L$it") }
            linkerOpts("-L${mpvAndroidLibsRoot.resolve("x86_64")}")
            linkerOpts("-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384")
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeX86 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("x86")?.let { linkerOpts("-L$it") }
            linkerOpts("-L${mpvAndroidLibsRoot.resolve("x86")}")
            linkerOpts("-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384")
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }

    wasmJs {
        binaries.executable()
        browser()
    }

    // Native executables need libmpv at link time. The host prefix is
    // auto-detected (Homebrew on macOS, multiarch paths on Linux) so
    // `linkDebugExecutableMacosArm64` works without extra properties.
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("sdlscale") {
            defFile(project.file("src/nativeInterop/cinterop/sdlscale.def"))
            includeDirs(project.file("src/nativeInterop/cinterop"))
        }
        if (!name.startsWith("androidNative") && mpvLibraryDir != null) {
            binaries.configureEach { linkerOpts("-L$mpvLibraryDir") }
            if (name.startsWith("macos") || name.startsWith("linux")) {
                binaries.configureEach { linkerOpts("-Wl,-rpath,$mpvLibraryDir") }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":mpv-kmp"))
            }
        }

        // SDL/ImGui UI shared by JVM, Android and the desktop/native targets.
        val playerMain by creating {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.sdl.kmp)
                implementation(libs.imgui.kmp)
            }
        }

        jvmMain {
            dependsOn(playerMain)
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        getByName("androidMain") {
            dependsOn(playerMain)
        }

        val nativeMain by creating {
            dependsOn(playerMain)
        }
        val androidNativeMain by creating {
            dependsOn(nativeMain)
        }
        listOf("macosArm64", "macosX64", "linuxX64", "linuxArm64", "mingwX64").forEach { target ->
            getByName("${target}Main").dependsOn(nativeMain)
        }
        listOf(
            "androidNativeArm64",
            "androidNativeArm32",
            "androidNativeX64",
            "androidNativeX86",
        ).forEach { target ->
            getByName("${target}Main").dependsOn(androidNativeMain)
        }

        wasmJsMain {
            dependsOn(playerMain)
            dependencies {
                implementation(libs.kotlinx.browser)
                implementation(libs.kotlinx.coroutines.core)
                implementation(npm("@enaium/sdl-kmp", libs.versions.sdl.kmp.npm.get()))
                implementation(npm("@enaium/imgui-kmp", libs.versions.imgui.kmp.npm.get()))
            }
        }
    }
}

// The default Android libmpv tree is populated by the root fetch task.
// Link tasks must wait for it; an explicit MPV_ANDROID_LIBS_DIR needs no task.
if (usesFetchedMpvAndroidLibs) {
    val fetchAndroidMpvLibs = rootProject.tasks.named("fetchAndroidMpvLibs")
    tasks.matching { it.name.startsWith("link") && it.name.contains("AndroidNative") }
        .configureEach { dependsOn(fetchAndroidMpvLibs) }
}

// SDL3 on macOS (via LWJGL) must run on the first thread.
tasks.withType<JavaExec>().configureEach {
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}

// Static browser app: Kotlin/Wasm module + flat index.html + the shared
// sample.webm, with the SDL/ImGui Emscripten runtimes copied from the npm
// packages installed by the Kotlin/Wasm toolchain.
val wasmOutput = layout.buildDirectory
    .dir("compileSync/wasmJs/main/developmentExecutable/kotlin")
val browserApp = layout.buildDirectory.dir("browserApp")
val wasmNpmModules = rootProject.layout.buildDirectory.dir("wasm/node_modules")
val wasmGeneratedResources = layout.buildDirectory.dir("generated/wasmResources")

val copyWasmAssets by tasks.registering(Copy::class) {
    dependsOn(":kotlinWasmNpmInstall")
    from(wasmNpmModules.map { it.dir("@enaium/sdl-kmp") }) {
        include("sdl_kmp_glue.js", "sdl_wasm.js", "sdl_wasm.wasm")
    }
    from(wasmNpmModules.map { it.dir("@enaium/imgui-kmp") }) {
        include("imgui_kmp_glue.js", "imgui_kmp_exports.js", "imgui_wasm.js", "imgui_wasm.wasm")
    }
    into(wasmGeneratedResources)
}

tasks.named<ProcessResources>("wasmJsProcessResources") {
    dependsOn(copyWasmAssets)
    from(copyWasmAssets)
}

val copyWebAssets by tasks.registering(Copy::class) {
    dependsOn("compileDevelopmentExecutableKotlinWasmJs", copyWasmAssets)
    from("src/commonMain/resources")
    from("src/wasmJsMain/resources")
    from(copyWasmAssets)
    from(wasmOutput) {
        include("**")
    }
    into(browserApp)
}
