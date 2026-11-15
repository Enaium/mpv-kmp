plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cn.enaium.mpv.example.player.jvm"
    compileSdk = 37
    defaultConfig {
        applicationId = "cn.enaium.mpv.example.player.jvm"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDir("../src/commonMain/resources")
    // Use an explicit MPV_ANDROID_LIBS_DIR when given; otherwise package the
    // pinned mpv-android libraries fetched by the root task. Without these the
    // JNI bridge cannot dlopen libmpv at runtime.
    val providedMpvLibs = providers.environmentVariable("MPV_ANDROID_LIBS_DIR").orNull
        ?: providers.gradleProperty("mpv.android.libs.dir").orNull
    val mpvLibsRoot = providedMpvLibs?.let(::File)
        ?: rootProject.layout.buildDirectory.dir("mpv-android-libs").get().asFile
    sourceSets["main"].jniLibs.srcDir(mpvLibsRoot)
    if (providedMpvLibs == null) {
        tasks.named("preBuild") {
            dependsOn(rootProject.tasks.named("fetchAndroidMpvLibs"))
        }
    }
}

dependencies {
    implementation(project(":examples:player"))
    implementation(libs.sdl.kmp)
    implementation(libs.sdl.kmp.android.jvm)
}
