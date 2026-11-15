package cn.enaium.mpv.example.player.ndk

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import org.libsdl.app.SDLActivity
import java.io.File

/**
 * Native Android launcher: SDLActivity loads libmain.so (the Kotlin/Native
 * player) and calls its exported SDL_main. The sample is copied before
 * SDLActivity starts the native thread so the native entry point can open it
 * from the app's files directory.
 */
class MainActivity : SDLActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        copySample()
        super.onCreate(savedInstanceState)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun getLibraries(): Array<String> = arrayOf("main")

    private fun copySample() {
        val sample = File(filesDir, "sample.webm")
        if (sample.isFile) return
        assets.open("sample.webm").use { input ->
            sample.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
}
