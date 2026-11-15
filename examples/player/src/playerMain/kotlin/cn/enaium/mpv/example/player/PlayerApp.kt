/*
 * Copyright (c) 2026 Enaium
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <https://www.gnu.org/licenses/>.
 */

package cn.enaium.mpv.example.player

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imgui.backends.sdl.toImTextureID
import cn.enaium.mpv.MPV
import cn.enaium.mpv.MPVClient
import cn.enaium.mpv.MPVProperty
import cn.enaium.mpv.MPVRenderContext
import cn.enaium.mpv.MPVVideoFrame
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess
import cn.enaium.sdl.SDLWindow
import cn.enaium.sdl.SDLWindowEventType
import cn.enaium.sdl.SDLWindowFlags
import kotlin.math.max
import kotlin.math.roundToInt

/** Host-owned frame loop; the browser drives it from a timer. */
typealias PlayerFrameDriver = (step: () -> Boolean, done: () -> Unit) -> Unit

/** Positions the HTML video under the shared ImGui canvas (wasm only). */
typealias WebVideoSurface = (
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    displayWidth: Float,
    displayHeight: Float,
    visible: Boolean,
) -> Unit

/**
 * A complete player: SDL3 window + SDL 2D renderer + Dear ImGui controls.
 * Desktop/native/Android use libmpv's software renderer feeding a texture;
 * the browser uses the same ImGui UI and positions the HTML video below it.
 *
 * Run it with `./gradlew :examples:player:jvmRun --args="--file movie.mp4"`.
 */
class PlayerApp(
    private val initialPath: String?,
    private val maxFrames: Int = Int.MAX_VALUE,
    private val noAudio: Boolean = false,
    private val providedClient: MPVClient? = null,
    private val webVideoSurface: WebVideoSurface? = null,
    private val uiScaleProvider: ((SDLWindow) -> Float)? = null,
) {

    private lateinit var window: SDLWindow
    private lateinit var renderer: SDLRenderer
    private lateinit var imgui: ImGuiSdlBackend
    private lateinit var backend: ImGuiSdlRendererBackend
    private lateinit var player: MPVClient

    private var renderContext: MPVRenderContext? = null
    private var texture: SDLTexture? = null
    private var textureId: ImTextureID? = null
    private var renderWidth = 0
    private var renderHeight = 0

    private var mediaPath = initialPath.orEmpty()
    private var pendingLoad: String? = initialPath?.takeIf { it.isNotBlank() }
    private var lastError: String? = null
    private var seekDragging = false
    private var seekTarget = 0f
    private var finished = false
    private var firstFrameLogged = false
    private var androidUi = false
    private var uiScale = 1f

    private val isWeb: Boolean get() = MPV.platform == "wasmJs"

    fun run(driver: PlayerFrameDriver? = null) {
        SDL.setMainReady()
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
            check(SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
                "SDL_Init failed: ${SDL.error()}"
            }
            println("video init fell back to the dummy driver — running headless")
        }
        androidUi = !isWeb && SDL.getCurrentVideoDriver() == "android"
        if (androidUi) {
            // SDL's Android window logic otherwise lets a resizable window
            // follow the device orientation; the player UI is landscape-only.
            SDL.setHint("SDL_ORIENTATIONS", "LandscapeLeft LandscapeRight")
            // ImGui's SDL backend consumes mouse events; let the Android
            // touchscreen synthesize them.
            SDL.setHint("SDL_TOUCH_MOUSE_EVENTS", "1")
        }

        window = SDL.createWindow(
            title = "mpv-kmp player",
            width = 1280,
            height = 800,
            flags = SDLWindowFlags.HIGH_PIXEL_DENSITY or
                (if (androidUi) 0u else SDLWindowFlags.RESIZABLE),
        )
        if (androidUi) {
            // Android reports physical pixels as the logical window size, so
            // the imgui framebuffer scale is 1. Ask the host for the real
            // display density instead; this keeps fonts and touch targets
            // readable without changing desktop/wasm layout.
            uiScale = runCatching { uiScaleProvider?.invoke(window) }.getOrNull()
                ?.takeIf { it.isFinite() && it > 1f } ?: 1f
        }
        renderer = SDL.createRenderer(window)
        val context = ImGui.createContext()
        imgui = ImGuiSdlBackend(window)
        backend = ImGuiSdlRendererBackend(renderer)

        player = providedClient ?: MPV.create {
            setOption("config", "no")
            setOption("terminal", "no")
            setOption("idle", "yes")
            setOption("keep-open", "yes")
            setOption("vo", "libmpv")
            if (noAudio) setOption("ao", "null")
            setOption("hwdec", "no")
            setOption("hr-seek", "yes")
            setOption("input-default-bindings", "no")
            setOption("input-vo-keyboard", "no")
        }

        try {
            imgui.init()
            val fonts = ImGui.getIO().fonts
            val density = if (androidUi) {
                uiScale
            } else {
                maxOf(imgui.framebufferScale.x, imgui.framebufferScale.y, 1f)
            }
            fonts.addFontDefault(
                ImFontConfig(
                    sizePixels = 15f * density,
                    rasterizerDensity = density,
                ),
            )
            check(fonts.build()) { "font atlas build failed" }
            val texData = fonts.getTexDataAsRGBA32()
            val fontTexture = backend.uploadFontTexture(texData.pixels, texData.width, texData.height)
            fonts.setTexID(fontTexture)

            player.initialize()
            if (!isWeb) player.requestLogMessages("warn")
            println("mpv-kmp UI ${MPV.clientApiVersion()} via ${MPV.platform}, client ${player.clientName}")

            var frame = 0
            fun step(): Boolean {
                while (true) {
                    val event = SDL.pollEvent() ?: break
                    when (event) {
                        is SDLEvent.Quit -> return false
                        is SDLEvent.Window -> if (event.type == SDLWindowEventType.CLOSE_REQUESTED) return false
                        is SDLEvent.MouseButton -> {
                            // Android touch-to-mouse synthesis emits a button
                            // with no preceding motion; ImGui must see the
                            // position before the button event to hit a widget.
                            ImGui.getIO().addMousePosEvent(event.x, event.y)
                            imgui.processEvent(event)
                        }
                        else -> imgui.processEvent(event)
                    }
                }

                imgui.newFrame()
                drawUi()
                ImGui.render()

                renderer.drawColor = cn.enaium.sdl.SDLColor(12, 13, 18, 255)
                renderer.clear()
                backend.renderDrawData(ImGui.getDrawData())
                renderer.present()
                if (!isWeb) SDL.delay(1)
                frame++
                return frame < maxFrames
            }

            if (driver != null) {
                // The browser owns the loop; it calls `done` after the last frame.
                driver(::step) { finish(context) }
            } else {
                while (step()) {
                }
                finish(context)
            }
        } catch (t: Throwable) {
            finish(context)
            throw t
        }
    }

    private fun drawUi() {
        val io = ImGui.getIO()
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(io.displaySize, ImGuiCond.ALWAYS)
        val flags = ImGuiWindowFlags.NO_TITLE_BAR or
            ImGuiWindowFlags.NO_RESIZE or
            ImGuiWindowFlags.NO_MOVE or
            ImGuiWindowFlags.NO_SCROLLBAR or
            ImGuiWindowFlags.NO_SCROLL_WITH_MOUSE or
            ImGuiWindowFlags.NO_SAVED_SETTINGS or
            ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS
        ImGui.begin("mpv-kmp player", flags = flags)

        val duration = propertyDouble(MPVProperty.DURATION) ?: 0.0
        val position = propertyDouble(MPVProperty.TIME_POS) ?: 0.0
        val paused = propertyBoolean(MPVProperty.PAUSE) ?: true
        val volume = propertyDouble(MPVProperty.VOLUME) ?: 100.0
        val muted = propertyBoolean(MPVProperty.MUTE) ?: false

        ImGui.text("mpv-kmp")
        ImGui.sameLine()
        if (ImGui.button(if (window.fullscreen) "Windowed" else "Fullscreen", ImVec2(110f * uiScale, 0f))) {
            window.fullscreen = !window.fullscreen
        }
        ImGui.setNextItemWidth((ImGui.getContentRegionAvail().x - 76f * uiScale).coerceAtLeast(120f * uiScale))
        ImGui.inputText("##path", mediaPath)?.let { mediaPath = it }
        ImGui.sameLine()
        if (ImGui.button("Load", ImVec2(64f * uiScale, 0f))) load(mediaPath)

        ImGui.spacing()
        val available = ImGui.getContentRegionAvail()
        val controlHeight = ImGui.getFrameHeightWithSpacing() * 2 + 28f * uiScale
        val videoWidth = available.x.coerceAtLeast(160f)
        val videoHeight = (available.y - controlHeight).coerceAtLeast(120f)
        val scale = maxOf(imgui.framebufferScale.x, imgui.framebufferScale.y, 1f)
        if (!isWeb) {
            ensureRenderTarget(
                width = (videoWidth * scale).roundToInt().coerceAtLeast(1),
                height = (videoHeight * scale).roundToInt().coerceAtLeast(1),
            )
        }
        pendingLoad?.let { path ->
            pendingLoad = null
            load(path)
        }

        val start = ImGui.getCursorScreenPos()
        val end = ImVec2(start.x + videoWidth, start.y + videoHeight)
        val drawList = ImGui.getWindowDrawList()
        drawList.DrawRectFilled(start, end, 0xFF101218.toInt())
        if (isWeb) {
            webVideoSurface?.invoke(
                start.x,
                start.y,
                videoWidth,
                videoHeight,
                io.displaySize.x,
                io.displaySize.y,
                true,
            )
            ImGui.dummy(ImVec2(videoWidth, videoHeight))
        } else {
            val currentTexture = textureId
            if (currentTexture != null) {
                ImGui.image(currentTexture, ImVec2(videoWidth, videoHeight))
            } else {
                drawList.DrawText(
                    ImVec2(start.x + 16f, start.y + 16f),
                    "No video frame — load a media file",
                    0xFF8A8A96.toInt(),
                )
                ImGui.dummy(ImVec2(videoWidth, videoHeight))
            }
        }

        ImGui.spacing()
        if (ImGui.button(if (paused) "Play" else "Pause", ImVec2(72f * uiScale, 0f))) {
            togglePlayback(paused)
        }
        ImGui.sameLine()
        if (ImGui.button("Stop", ImVec2(72f * uiScale, 0f))) {
            player.command("stop")
            seekTarget = 0f
        }
        ImGui.sameLine()
        val mute = booleanArrayOf(muted)
        if (ImGui.checkbox("Mute", mute)) {
            player.setPropertyFlag(MPVProperty.MUTE, mute[0])
        }
        ImGui.sameLine(spacing = 10f * uiScale)
        ImGui.text("Vol")
        ImGui.sameLine()
        ImGui.setNextItemWidth(150f * uiScale)
        val volumeValue = floatArrayOf(volume.toFloat())
        if (ImGui.sliderFloat("##volume", volumeValue, 0f, 100f, "%.0f%%")) {
            player.setPropertyDouble(MPVProperty.VOLUME, volumeValue[0].toDouble())
        }

        ImGui.spacing()
        val shownPosition = if (seekDragging) seekTarget else position.toFloat()
        val seekValue = floatArrayOf(shownPosition)
        ImGui.setNextItemWidth((available.x - 170f * uiScale).coerceAtLeast(120f * uiScale))
        if (ImGui.sliderFloat(
                "##seek",
                seekValue,
                0f,
                max(duration.toFloat(), 0.01f),
                "%.1f s",
            )
        ) {
            seekTarget = seekValue[0]
            seekDragging = true
            player.command("seek", seekTarget.toString(), "absolute")
        }
        if (!ImGui.isItemActive()) {
            seekDragging = false
        }
        ImGui.sameLine()
        ImGui.text("${formatTime(shownPosition.toDouble())} / ${formatTime(duration)}")

        lastError?.let {
            ImGui.textDisabled(it)
        }

        if (!io.wantTextInput) {
            if (ImGui.isKeyPressed(ImGuiKey.SPACE)) {
                togglePlayback(paused)
            }
            if (ImGui.isKeyPressed(ImGuiKey.LEFT_ARROW)) {
                player.command("seek", "-5", "relative")
            }
            if (ImGui.isKeyPressed(ImGuiKey.RIGHT_ARROW)) {
                player.command("seek", "5", "relative")
            }
            if (ImGui.isKeyPressed(ImGuiKey.UP_ARROW)) {
                player.setPropertyDouble(MPVProperty.VOLUME, (volume + 5.0).coerceAtMost(100.0))
            }
            if (ImGui.isKeyPressed(ImGuiKey.DOWN_ARROW)) {
                player.setPropertyDouble(MPVProperty.VOLUME, (volume - 5.0).coerceAtLeast(0.0))
            }
        }

        ImGui.end()
    }

    private fun togglePlayback(paused: Boolean) {
        val duration = propertyDouble(MPVProperty.DURATION) ?: 0.0
        val position = propertyDouble(MPVProperty.TIME_POS) ?: 0.0
        val ended = propertyBoolean(MPVProperty.EOF_REACHED) == true ||
            (duration > 0.0 && position >= duration - 0.05)
        if (ended) {
            player.command("seek", "0", "absolute")
            seekTarget = 0f
            player.setPropertyFlag(MPVProperty.PAUSE, false)
        } else {
            player.setPropertyFlag(MPVProperty.PAUSE, !paused)
        }
    }

    private fun ensureRenderTarget(width: Int, height: Int) {
        if (renderContext != null && width == renderWidth && height == renderHeight) {
            updateFrame()
            return
        }
        renderContext?.close()
        renderContext = null
        texture?.let {
            backend.unregisterTexture(it)
            texture = null
            textureId = null
        }
        renderWidth = width
        renderHeight = height
        renderContext = try {
            player.createRenderContext(width, height)
        } catch (e: Exception) {
            lastError = "render: ${e.message}"
            println(lastError)
            null
        }
        updateFrame()
    }

    private fun updateFrame() {
        val context = renderContext ?: return
        if (!context.update()) return
        val frame = context.render() ?: return
        if (!firstFrameLogged) {
            firstFrameLogged = true
            println("video frame: ${frame.width}x${frame.height} ${frame.format.mpvName}")
        }
        uploadFrame(frame)
        context.reportSwap()
    }

    private fun uploadFrame(frame: MPVVideoFrame) {
        var target = texture
        if (target == null || target.size.x.toInt() != frame.width || target.size.y.toInt() != frame.height) {
            target?.let { backend.unregisterTexture(it) }
            target = renderer.createTexture(
                format = SDLPixelFormat.RGBA32,
                access = SDLTextureAccess.STREAMING,
                width = frame.width,
                height = frame.height,
            )
            target.scaleMode = SDLScaleMode.LINEAR
            target.blendMode = SDLBlendMode.NONE
            backend.registerTexture(target)
            texture = target
            textureId = target.toImTextureID()
        }
        target.update(rect = null, pixels = frame.pixels, pitch = frame.stride)
    }

    private fun load(path: String) {
        if (path.isBlank()) return
        try {
            player.command("loadfile", path)
            lastError = null
            seekTarget = 0f
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    private fun propertyDouble(name: String): Double? =
        try {
            player.getPropertyDouble(name)
        } catch (_: Exception) {
            null
        }

    private fun propertyBoolean(name: String): Boolean? =
        try {
            player.getPropertyBoolean(name)
        } catch (_: Exception) {
            null
        }

    private fun finish(context: cn.enaium.imgui.ImGuiContext) {
        if (finished) return
        finished = true
        if (isWeb) webVideoSurface?.invoke(0f, 0f, 0f, 0f, 1f, 1f, false)
        renderContext?.close()
        renderContext = null
        texture?.let { backend.unregisterTexture(it) }
        texture = null
        textureId = null
        player.close()
        backend.close()
        ImGui.destroyContext(context)
        renderer.close()
        window.close()
        SDL.quit()
    }

    private fun formatTime(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).roundToInt()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}
