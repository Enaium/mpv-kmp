plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = rootProject.group
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":mpv-kmp"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("cn.enaium.mpv.examples.cli.MainKt")
}
