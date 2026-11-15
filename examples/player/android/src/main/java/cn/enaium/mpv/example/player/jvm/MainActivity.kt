package cn.enaium.mpv.example.player.jvm

import android.os.Bundle
import android.util.Log
import cn.enaium.mpv.example.player.PlayerApp
import cn.enaium.mpv.example.player.hideSystemBars
import cn.enaium.sdl.SDL
import org.libsdl.app.SDLActivity
import java.io.File

/**
 * JVM Android entry point. SDLActivity loads the SDL/ImGui/libmpv JNI bridges
 * and invokes [main] on SDL's dedicated thread. The player itself is the same
 * shared PlayerApp used by the desktop and wasm targets.
 */
class MainActivity : SDLActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun getLibraries(): Array<String> = arrayOf("sdl_jni", "mpv_jni")

    override fun main() {
        try {
            val sample = File(cacheDir, "sample.webm")
            if (!sample.isFile) {
                assets.open("sample.webm").use { input ->
                    sample.outputStream().use { output -> input.copyTo(output) }
                }
            }
            PlayerApp(
                initialPath = sample.absolutePath,
                uiScaleProvider = { resources.displayMetrics.density },
            ).run()
        } catch (t: Throwable) {
            Log.e(TAG, "mpv-kmp player failed", t)
        } finally {
            SDL.quit()
        }
    }

    private companion object {
        const val TAG = "MpvPlayerActivity"
    }
}
