import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

// ==================== Project coordinates ====================
allprojects {
    group = "cn.enaium.mpv"
    // Tracks the pinned libmpv release (0.42.0); the trailing component is
    // this library's own revision.
    version = "0.42.0.1"
}

// ==================== Android example runtime ====================
// mpv-android's release APK is the canonical Android libmpv build: libmpv
// plus its FFmpeg/libplacebo/libass dependency set, for every ABI this
// repository targets. The examples use it when MPV_ANDROID_LIBS_DIR is not
// set, so `:examples:player:android*:assembleDebug` works from a clean
// checkout. The APK is GPL-2.0-or-later; it is never included in the published
// mpv-kmp artifacts.
val mpvAndroidRelease = "2026-09-17"
val mpvAndroidApkUrl =
    "https://github.com/mpv-android/mpv-android/releases/download/$mpvAndroidRelease/" +
        "app-default-universal-release.apk"
val mpvAndroidApkSha256 = "c3b505e45b919b767b9867e7f0d16fd823d77bc7c9a6e6b181b80ea60c018a70"
val mpvAndroidApk = layout.buildDirectory.file("downloads/mpv-android-$mpvAndroidRelease.apk")
val mpvAndroidLibs = layout.buildDirectory.dir("mpv-android-libs")

abstract class DownloadVerifiedFile : DefaultTask() {

    @get:Input
    abstract val sourceUrl: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val target = outputFile.get().asFile
        if (target.isFile && digest(target) == sha256.get()) return
        target.parentFile.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        logger.lifecycle("Downloading ${sourceUrl.get()}")
        URI(sourceUrl.get()).toURL().openStream().use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = digest(partial)
        check(actual == sha256.get()) {
            "Checksum mismatch for ${sourceUrl.get()}: expected ${sha256.get()}, got $actual"
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

val downloadMpvAndroidApk = tasks.register<DownloadVerifiedFile>("downloadMpvAndroidApk") {
    sourceUrl.set(mpvAndroidApkUrl)
    sha256.set(mpvAndroidApkSha256)
    outputFile.set(mpvAndroidApk)
}

val fetchAndroidMpvLibs = tasks.register<Sync>("fetchAndroidMpvLibs") {
    group = "android"
    description = "Extracts the pinned mpv-android native libraries into build/mpv-android-libs/<abi>."
    dependsOn(downloadMpvAndroidApk)
    from(zipTree(mpvAndroidApk.get().asFile)) {
        include("lib/*/*.so")
        // libplayer.so is mpv-android's activity JNI, not part of libmpv.
        exclude("lib/*/libplayer.so")
        eachFile { path = path.substringAfter("lib/") }
    }
    into(mpvAndroidLibs)
}
