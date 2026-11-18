@file:Suppress("DEPRECATION")

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

@OptIn(ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

// Headers are taken from the pinned mpv submodule (v0.42.0,
// b0841e8c4d156b8403dc1876c4cbd9011eb583f3). The library itself is resolved
// at application link time (`-lmpv`), matching how libmpv is packaged on each
// operating system; see README.md.
val mpvHeaders = rootProject.file("mpv/include")

// Optional non-system libmpv prefix (e.g. one built from the submodule with
// meson --prefix). Consumers can also pass `-L` themselves in the final app
// link; this property is for local builds/tests against a custom install.
val mpvLibraryDir: String? = providers.gradleProperty("mpv.library.dir").orNull
    ?: providers.environmentVariable("MPV_LIBRARY_DIR").orNull

kotlin {
    // ==================== JVM ====================
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // ==================== Android (JVM) ====================
    // Android shares the JVM JNI implementation. The :android-jvm artifact
    // packages the per-ABI libmpv_jni.so into jniLibs.
    android {
        namespace = "cn.enaium.mpv"
        compileSdk = 36
        minSdk = 24

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    // ==================== Kotlin/Native ====================
    macosArm64()
    macosX64()

    linuxX64()
    linuxArm64()

    mingwX64()

    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()

    // ==================== Web (Wasm) ====================
    // The browser backend is implemented against HTMLMediaElement; there is
    // no libmpv binary for wasm.
    wasmJs {
        browser()
        binaries.executable()
        generateTypeScriptDefinitions()
    }

    // ==================== cinterop for every native target ====================
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main") {
            cinterops.create("mpv") {
                defFile(project.file("src/nativeInterop/cinterop/mpv.def"))
                includeDirs(mpvHeaders, project.file("src/nativeInterop/cinterop"))
                if (mpvLibraryDir != null) {
                    linkerOpts("-L$mpvLibraryDir")
                }
            }
            defaultSourceSet.kotlin.srcDir("src/nativeMain/kotlin")
        }
    }

    // ==================== Source sets ====================
    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        jvmMain {
            dependencies {
                // The JNI bridge discovers libmpv at runtime. Bundle every
                // desktop classifier; NativeLoader picks the host one.
                runtimeOnly(project(":jni-jvm-linux-x86_64"))
                runtimeOnly(project(":jni-jvm-linux-aarch64"))
                runtimeOnly(project(":jni-jvm-darwin-x86_64"))
                runtimeOnly(project(":jni-jvm-darwin-aarch64"))
                runtimeOnly(project(":jni-jvm-windows-x86_64"))
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }

        getByName("androidMain") {
            // Android runs the same JVM JNI code; System.loadLibrary finds
            // libmpv_jni.so in the APK's jniLibs (packaged by :android-jvm).
            kotlin.srcDir("src/jvmMain/kotlin")
            dependencies {
                api(project(":android-jvm"))
            }
        }

        wasmJsMain {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }
    }

    // ==================== Native tests ====================
    // Test executables need to find libmpv at link time and at runtime. Only
    // the host-native target is runnable on a given machine; the Homebrew
    // prefix adds the search and rpath entries without baking them into the
    // published klib (cinterop keeps just `-lmpv`).
    val hostOsName = System.getProperty("os.name").orEmpty().lowercase()
    if (hostOsName.contains("mac") || hostOsName.contains("darwin")) {
        val libraryDir = mpvLibraryDir ?: "/opt/homebrew/lib"
        targets.withType<KotlinNativeTarget>().configureEach {
            if (name.startsWith("macos")) {
                binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
                    linkerOpts("-L$libraryDir", "-Wl,-rpath,$libraryDir")
                }
            }
        }
    }
    if (hostOsName.contains("linux")) {
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val libraryDir = when {
            arch.contains("aarch64") -> "/usr/lib/aarch64-linux-gnu"
            else -> "/usr/lib/x86_64-linux-gnu"
        }
        targets.withType<KotlinNativeTarget>().configureEach {
            if (name.startsWith("linux")) {
                binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
                    linkerOpts("-L$libraryDir")
                }
            }
        }
    }
}

// ==================== Publishing ====================
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "mpv-kmp",
        version = null,
    )

    pom {
        name.set("mpv-kmp")
        description.set(
            "Kotlin Multiplatform bindings for libmpv 0.42.0. " +
                "Native targets bind client.h through cinterop; the JVM and Android targets use " +
                "a JNI bridge that loads libmpv at runtime; wasmJs provides an " +
                "HTMLMediaElement-backed implementation of the same API.",
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

// ==================== wasm: npm package ====================
// The library's wasmJs target produces a standalone Kotlin/Wasm module with
// @JsExport functions (MPV.web.kt). Publish that module as @enaium/mpv-kmp so
// browser apps can use the MPVWeb backend from JavaScript/TypeScript without
// adding the example player to the package.
val npmPackageVersion: String = project.version.toString().also { semver ->
    require(Regex("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$").matches(semver)) {
        "Project version '$semver' is not valid npm semver"
    }
}
val wasmExecutableDir = layout.buildDirectory.dir(
    "compileSync/wasmJs/main/productionExecutable/kotlin",
)
val wasmNpmDir = layout.buildDirectory.dir("npm").get().asFile

val assembleWasmNpm by tasks.registering {
    group = "build"
    description = "Stages the @enaium/mpv-kmp Kotlin/Wasm npm package in build/npm."
    dependsOn(
        "wasmJsProductionExecutableCompileSync",
        "wasmJsProductionExecutableValidateGeneratedByCompilerTypeScript",
    )
    inputs.dir(wasmExecutableDir)
    inputs.property("npmPackageVersion", npmPackageVersion)
    outputs.dir(wasmNpmDir)
    doLast {
        wasmNpmDir.deleteRecursively()
        wasmNpmDir.mkdirs()
        copy {
            from(wasmExecutableDir) {
                include("*.mjs", "*.wasm", "*.d.mts", "*.map")
            }
            into(wasmNpmDir)
        }
        rootProject.file("LICENSE").copyTo(wasmNpmDir.resolve("LICENSE"), overwrite = true)
        wasmNpmDir.resolve("package.json").writeText(
            """
            {
              "name": "@enaium/mpv-kmp",
              "version": "$npmPackageVersion",
              "description": "Kotlin/Wasm browser backend for the mpv-kmp MPVWeb API",
              "license": "GPL-2.0-or-later",
              "author": "Enaium",
              "type": "module",
              "keywords": [
                "mpv",
                "libmpv",
                "kotlin",
                "wasm",
                "video",
                "browser"
              ],
              "exports": {
                ".": {
                  "types": "./mpv-kmp-mpv-kmp.d.mts",
                  "import": "./mpv-kmp-mpv-kmp.mjs"
                },
                "./package.json": "./package.json"
              },
              "files": [
                "mpv-kmp-mpv-kmp.mjs",
                "mpv-kmp-mpv-kmp.import-object.mjs",
                "mpv-kmp-mpv-kmp.js-builtins.mjs",
                "mpv-kmp-mpv-kmp.wasm",
                "mpv-kmp-mpv-kmp.wasm.map",
                "mpv-kmp-mpv-kmp.d.mts",
                "LICENSE",
                "README.md"
              ],
              "homepage": "https://github.com/Enaium/mpv-kmp",
              "repository": {
                "type": "git",
                "url": "git+https://github.com/Enaium/mpv-kmp.git"
              },
              "bugs": {
                "url": "https://github.com/Enaium/mpv-kmp/issues"
              },
              "publishConfig": {
                "access": "public",
                "registry": "https://registry.npmjs.org/"
              }
            }
            """.trimIndent() + "\n",
        )
        wasmNpmDir.resolve("README.md").writeText(
            """
            # @enaium/mpv-kmp

            Kotlin/Wasm browser backend for [mpv-kmp](https://github.com/Enaium/mpv-kmp).
            It implements the `MPVWeb` API on an `HTMLVideoElement`; it does not
            bundle libmpv or the example player.

            ```js
            import * as mpv from '@enaium/mpv-kmp'

            const video = document.querySelector('video')
            const player = mpv.createPlayerFor(video)
            mpv.initialize(player)
            mpv.command(player, 'loadfile "video.mp4"')
            mpv.setFlag(player, 'pause', false)
            ```

            `createPlayer()` creates and appends a `<video controls>` element.
            Player handles are opaque references; use the exported functions to
            control them. `getString`, `getNumber`, and `getFlag` read mpv-style
            properties such as `time-pos`, `duration`, `pause`, and `volume`.
            Call `close(player)` when finished.

            The package is a compiled Kotlin/Wasm module. Its generated
            TypeScript declarations are included as `mpv-kmp-mpv-kmp.d.mts`.
            """.trimIndent() + "\n",
        )
    }
}

val publishWasmNpm by tasks.registering(Exec::class) {
    group = "publishing"
    description = "Publishes @enaium/mpv-kmp to npm (token: npm.publish.key or NODE_AUTH_TOKEN)."
    dependsOn(assembleWasmNpm)
    workingDir = wasmNpmDir
    val npmKey = providers.gradleProperty("npm.publish.key")
    val npmTag = providers.gradleProperty("npm.publish.tag").orNull
        ?.takeIf { it.isNotBlank() } ?: "latest"
    doFirst {
        val key = npmKey.orNull?.takeIf { it.isNotBlank() }
            ?: System.getenv("NODE_AUTH_TOKEN")?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "npm.publish.key is not set: add it to ~/.gradle/gradle.properties, " +
                    "pass -Pnpm.publish.key=<token>, or set NODE_AUTH_TOKEN",
            )
        // NODE_AUTH_TOKEN is expanded by npm through the environment, so the
        // token never lands in package.json/tarball; .npmrc is on npm's
        // never-publish list anyway.
        environment("NODE_AUTH_TOKEN", key)
        wasmNpmDir.resolve(".npmrc").writeText(
            "//registry.npmjs.org/:_authToken=\${NODE_AUTH_TOKEN}\n",
        )
        val args = mutableListOf("npm", "publish", "--access", "public", "--tag", npmTag)
        if (providers.gradleProperty("npm.provenance").orNull?.toBoolean() == true) {
            args += "--provenance"
        }
        if (providers.gradleProperty("npm.publish.dryRun").orNull?.toBoolean() == true) {
            args += "--dry-run"
        }
        commandLine(args)
    }
}
