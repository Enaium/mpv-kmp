pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenLocal()
        mavenCentral()
        // The Kotlin/JS plugin resolves its Node.js distribution from here.
        // PREFER_SETTINGS keeps project-level repositories out of the way, so
        // the distribution has to be declared next to the Maven ones.
        ivy("https://nodejs.org/dist") {
            name = "NodeDistributions"
            patternLayout {
                artifact("v[revision]/[artifact]-v[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "BinaryenReleases"
            patternLayout {
                artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "YarnReleases"
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]).[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
    }
}

rootProject.name = "mpv-kmp"

include(":mpv-kmp")

include(":examples:mpv_cli")
include(":examples:player")
include(":examples:player:android")
include(":examples:player:android-native")

// Per-OS/arch JNI artifacts that bundle the prebuilt libmpv_jni shared
// library as a classpath resource. NativeLoader extracts the matching one at
// runtime.
listOf(
    "linux-x86_64",
    "linux-aarch64",
    "darwin-x86_64",
    "darwin-aarch64",
    "windows-x86_64",
).forEach { classifier ->
    val name = ":jni-jvm-$classifier"
    include(name)
    project(name).projectDir = file("jni/jvm/$classifier")
}

// Android library packaging the per-ABI libmpv_jni.so plus the runtime
// permissions/resources Android apps need.
include(":android-jvm")
project(":android-jvm").projectDir = file("jni/android-jvm")
