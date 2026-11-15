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

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package cn.enaium.mpv

import kotlinx.browser.document
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.JsFun
import kotlin.js.JsAny
import kotlin.js.unsafeCast

/**
 * Browser backend.
 *
 * There is no libmpv for WebAssembly: mpv's decoding, filtering, protocols
 * and scripting stack depends on native libraries and APIs. This backend
 * implements the same [MPVClient] contract on top of `HTMLVideoElement`, and
 * [MPVClient.capabilities] reports exactly which parts are real, partial,
 * emulated or unsupported.
 *
 * The common playback API (loadfile/pause/seek/volume/properties/events)
 * works; mpv-only properties, commands, shaders, filters, protocols and the
 * render API throw [MPVException] with a negative `MPV_ERROR_UNSUPPORTED` /
 * `MPV_ERROR_PROPERTY_NOT_FOUND` code instead of silently doing nothing.
 */
object MPVWeb {
    /** Creates a player with a new `<video controls>` element appended to `<body>`. */
    fun create(): MPVClient = WebMPVClient(null)

    /** Creates a player that renders into an existing `<video>` element. */
    fun create(video: HTMLVideoElement): MPVClient = WebMPVClient(video)
}

private class WebMPVClient(externalVideo: HTMLVideoElement?) :
    MPVClientBase(MPVCapabilities.web()) {

    private val ownsElement = externalVideo == null
    private val video: HTMLVideoElement = externalVideo ?: newVideoElement()

    private val listeners = mutableListOf<Pair<String, (Event) -> Unit>>()
    private val observers = linkedMapOf<Long, Observer>()
    private val lastValues = linkedMapOf<Long, MPVNode?>()
    private var logLevel = "no"

    private data class Observer(val name: String, val format: MPVFormat)

    override val clientName: String get() = "web"

    override fun initialize() {
        requireOpen()
        video.playsInline = true
        video.preload = "auto"
        listen("loadstart") { emit(MPVEvent.StartFile(0L)) }
        listen("loadedmetadata") {
            emit(MPVEvent.FileLoaded)
            emit(MPVEvent.VideoReconfig)
            notifyObservers()
        }
        listen("durationchange") { notifyObservers() }
        listen("timeupdate") { notifyObservers() }
        listen("volumechange") { notifyObservers() }
        listen("ratechange") { notifyObservers() }
        listen("play") { notifyObservers() }
        listen("pause") { notifyObservers() }
        listen("seeking") { emit(MPVEvent.Seek) }
        listen("seeked") { emit(MPVEvent.PlaybackRestart) }
        listen("playing") { emit(MPVEvent.PlaybackRestart) }
        listen("ended") {
            emit(
                MPVEvent.EndFile(
                    reason = MPVEndFileReason.EOF,
                    error = 0,
                    playlistEntryId = 0L,
                    playlistInsertId = 0L,
                    playlistInsertNumEntries = 0,
                ),
            )
            emit(MPVEvent.Idle)
            notifyObservers()
        }
        listen("error") {
            val code = video.error?.code?.toInt() ?: 0
            emit(
                MPVEvent.EndFile(
                    reason = MPVEndFileReason.ERROR,
                    error = MPVError.LOADING_FAILED,
                    playlistEntryId = 0L,
                    playlistInsertId = 0L,
                    playlistInsertNumEntries = 0,
                ),
            )
            emit(
                MPVEvent.LogMessage(
                    prefix = "web",
                    level = "error",
                    text = "HTMLMediaElement error $code",
                    logLevel = 4,
                ),
            )
        }
        markInitialized()
    }

    override fun setOption(name: String, value: String) {
        requireOpen()
        when (name) {
            "pause" -> video.autoplay = false
            "volume" -> video.volume = (value.toDoubleOrNull() ?: 100.0).coerceIn(0.0, 100.0) / 100.0
            "mute" -> video.muted = value == "yes" || value == "true"
            "loop-file", "loop-playlist" -> video.loop = value == "yes" || value == "true" || value == "inf"
            "speed" -> video.playbackRate = value.toDoubleOrNull() ?: 1.0
            "fullscreen", "vo", "hwdec", "ao", "audio-device", "config", "input-default-bindings" -> Unit
            else -> throw MPVException(
                MPVError.OPTION_NOT_FOUND,
                "The web backend does not support option '$name'",
            )
        }
    }

    override fun command(vararg args: String): MPVNode? {
        requireInitialized()
        if (args.isEmpty()) throw MPVException(MPVError.INVALID_PARAMETER, "Empty command")
        when (args[0]) {
            "loadfile" -> {
                val path = args.getOrNull(1)
                    ?: throw MPVException(MPVError.INVALID_PARAMETER, "loadfile requires a path")
                video.src = path
                video.load()
                playSafelyJs(video)
            }

            "stop" -> {
                video.pause()
                video.removeAttribute("src")
                video.load()
                emit(
                    MPVEvent.EndFile(
                        MPVEndFileReason.STOP, 0, 0L, 0L, 0,
                    ),
                )
            }

            "cycle" -> {
                val property = args.getOrNull(1) ?: "pause"
                if (property == "pause") {
                    if (video.paused) playSafelyJs(video) else video.pause()
                } else {
                    throw unsupportedCommand(args[0])
                }
            }

            "seek" -> {
                val target = args.getOrNull(1)?.toDoubleOrNull()
                    ?: throw MPVException(MPVError.INVALID_PARAMETER, "seek requires a number")
                val mode = args.getOrNull(2) ?: "relative"
                val wasEnded = video.ended
                video.currentTime = when (mode) {
                    "absolute" -> target
                    "absolute-percent" -> if (video.duration.isFinite()) video.duration * target / 100.0 else 0.0
                    "relative-percent" -> video.currentTime + (if (video.duration.isFinite()) {
                        video.duration * target / 100.0
                    } else {
                        0.0
                    })

                    else -> video.currentTime + target
                }
                // HTMLMediaElement ignores a seek from the ended state until
                // playback is nudged; do it so the progress bar keeps working
                // after the file finished.
                if (wasEnded) playSafelyJs(video)
            }

            "set" -> {
                val name = args.getOrNull(1)
                    ?: throw MPVException(MPVError.INVALID_PARAMETER, "set requires a property")
                val value = args.getOrNull(2)
                    ?: throw MPVException(MPVError.INVALID_PARAMETER, "set requires a value")
                setProperty(name, MPVNode.Text(value))
            }

            "quit" -> close()
            else -> throw unsupportedCommand(args[0])
        }
        return null
    }

    override fun commandString(command: String) {
        command(*tokenize(command).toTypedArray())
    }

    override fun commandAsync(replyUserdata: Long, vararg args: String) {
        try {
            command(*args)
            emit(MPVEvent.CommandReply(replyUserdata, 0, null))
        } catch (e: MPVException) {
            emit(MPVEvent.CommandReply(replyUserdata, e.errorCode, null))
        }
    }

    override fun getProperty(name: String, format: MPVFormat): MPVNode? {
        requireOpen()
        if (format == MPVFormat.NONE) return null
        val value = readProperty(name) ?: return null
        return coerce(value, format)
    }

    override fun setProperty(name: String, value: MPVNode) {
        requireOpen()
        when (name) {
            "pause" -> if (value.asFlag()) video.pause() else playSafelyJs(video)
            "volume" -> video.volume = value.asDouble().coerceIn(0.0, 100.0) / 100.0
            "mute" -> video.muted = value.asFlag()
            "speed" -> video.playbackRate = value.asDouble().coerceAtLeast(0.01)
            "loop-file", "loop-playlist" -> video.loop = value.asFlag()
            "time-pos" -> video.currentTime = value.asDouble().coerceAtLeast(0.0)
            "fullscreen" -> if (value.asFlag()) requestFullscreenJs(video) else exitFullscreenJs()
            "sub-visibility", "sid", "aid", "vid", "af", "vf", "lavfi" -> throw MPVException(
                MPVError.UNSUPPORTED,
                "The web backend does not implement libmpv property '$name'",
            )

            else -> throw MPVException(
                MPVError.PROPERTY_NOT_FOUND,
                "The web backend does not expose property '$name'",
            )
        }
        notifyObservers()
    }

    override fun setPropertyAsync(replyUserdata: Long, name: String, value: MPVNode) {
        try {
            setProperty(name, value)
            emit(MPVEvent.SetPropertyReply(replyUserdata, 0))
        } catch (e: MPVException) {
            emit(MPVEvent.SetPropertyReply(replyUserdata, e.errorCode))
        }
    }

    override fun observeProperty(name: String, replyUserdata: Long, format: MPVFormat): Long {
        requireOpen()
        observers[replyUserdata] = Observer(name, format)
        lastValues.remove(replyUserdata)
        notifyObserver(replyUserdata)
        return replyUserdata
    }

    override fun unobserveProperty(replyUserdata: Long) {
        requireOpen()
        observers.remove(replyUserdata)
        lastValues.remove(replyUserdata)
    }

    override fun requestLogMessages(level: String) {
        requireOpen()
        logLevel = level
        if (level != "no") {
            emit(
                MPVEvent.LogMessage(
                    prefix = "web",
                    level = "info",
                    text = "libmpv logging is unavailable in the browser; level '$level' recorded",
                    logLevel = 3,
                ),
            )
        }
    }

    override fun stopEventPumpAndDestroy() {
        listeners.forEach { (type, listener) -> video.removeEventListener(type, listener) }
        listeners.clear()
        video.pause()
        video.removeAttribute("src")
        video.load()
        if (ownsElement) removeElementJs(video)
        emit(MPVEvent.Shutdown)
    }

    private fun listen(type: String, handler: (Event) -> Unit) {
        video.addEventListener(type, handler)
        listeners += type to handler
    }

    private fun notifyObservers() {
        observers.keys.toList().forEach(::notifyObserver)
    }

    private fun notifyObserver(id: Long) {
        val observer = observers[id] ?: return
        val value = try {
            readProperty(observer.name)?.let { coerce(it, observer.format) }
        } catch (_: MPVException) {
            null
        }
        if (lastValues[id] != value) {
            lastValues[id] = value
            emit(MPVEvent.PropertyChange(observer.name, value, observer.format, id))
        }
    }

    private fun readProperty(name: String): MPVNode? = when (name) {
        "pause" -> MPVNode.Flag(video.paused)
        "time-pos" -> MPVNode.DoubleValue(video.currentTime)
        "time-remaining" -> if (video.duration.isFinite()) {
            MPVNode.DoubleValue((video.duration - video.currentTime).coerceAtLeast(0.0))
        } else {
            throw MPVException(MPVError.PROPERTY_UNAVAILABLE, "duration is unavailable")
        }

        "duration" -> if (video.duration.isFinite()) {
            MPVNode.DoubleValue(video.duration)
        } else {
            throw MPVException(MPVError.PROPERTY_UNAVAILABLE, "duration is unavailable")
        }

        "volume" -> MPVNode.DoubleValue(video.volume * 100.0)
        "mute" -> MPVNode.Flag(video.muted)
        "speed" -> MPVNode.DoubleValue(video.playbackRate)
        "loop-file", "loop-playlist" -> MPVNode.Flag(video.loop)
        "media-title" -> MPVNode.Text(video.title.ifBlank { video.currentSrc })
        "path" -> MPVNode.Text(video.currentSrc)
        "filename" -> MPVNode.Text(video.currentSrc.substringAfterLast('/'))
        "width" -> MPVNode.Int64(video.videoWidth.toLong())
        "height" -> MPVNode.Int64(video.videoHeight.toLong())
        "seekable" -> MPVNode.Flag(video.duration.isFinite())
        "eof-reached" -> MPVNode.Flag(video.ended)
        "idle-active" -> MPVNode.Flag(video.currentSrc.isBlank())
        "seeking" -> MPVNode.Flag(video.seeking)
        "paused-for-cache" -> MPVNode.Flag(video.readyState.toInt() < 3 && !video.paused)
        "fullscreen" -> MPVNode.Flag(false)
        "sub-visibility" -> MPVNode.Flag(false)
        "sub-text" -> MPVNode.Text("")
        "track-list", "chapters" -> MPVNode.ArrayValue(emptyList())
        "metadata" -> MPVNode.MapValue(emptyMap())
        "video-params" -> MPVNode.MapValue(
            mapOf(
                "w" to MPVNode.Int64(video.videoWidth.toLong()),
                "h" to MPVNode.Int64(video.videoHeight.toLong()),
            ),
        )

        else -> throw MPVException(
            MPVError.PROPERTY_NOT_FOUND,
            "The web backend does not expose property '$name'",
        )
    }

    private fun coerce(node: MPVNode, format: MPVFormat): MPVNode = when (format) {
        MPVFormat.NONE -> MPVNode.None
        MPVFormat.NODE, MPVFormat.NODE_ARRAY, MPVFormat.NODE_MAP, MPVFormat.BYTE_ARRAY -> node
        MPVFormat.STRING, MPVFormat.OSD_STRING -> MPVNode.Text(
            when (node) {
                is MPVNode.Text -> node.value
                is MPVNode.Flag -> if (node.value) "yes" else "no"
                is MPVNode.Int64 -> node.value.toString()
                is MPVNode.DoubleValue -> node.value.toString()
                else -> ""
            },
        )

        MPVFormat.FLAG -> MPVNode.Flag(node.asFlag())
        MPVFormat.INT64 -> MPVNode.Int64(node.asDouble().toLong())
        MPVFormat.DOUBLE -> MPVNode.DoubleValue(node.asDouble())
    }

    private fun unsupportedCommand(name: String): MPVException =
        MPVException(MPVError.UNSUPPORTED, "The web backend does not implement command '$name'")

    private fun MPVNode.asFlag(): Boolean = when (this) {
        is MPVNode.Flag -> value
        is MPVNode.Text -> value == "yes" || value == "true" || value == "1"
        is MPVNode.Int64 -> value != 0L
        is MPVNode.DoubleValue -> value != 0.0
        else -> false
    }

    private fun MPVNode.asDouble(): Double = when (this) {
        is MPVNode.Flag -> if (value) 1.0 else 0.0
        is MPVNode.Int64 -> value.toDouble()
        is MPVNode.DoubleValue -> value
        is MPVNode.Text -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
}

private fun newVideoElement(): HTMLVideoElement =
    document.createElement("video").unsafeCast<HTMLVideoElement>().also { video ->
        video.controls = true
        video.setAttribute("data-mpv-kmp", "")
        document.body?.appendChild(video)
    }

/** `play()` rejects under autoplay policies; playback still starts on user interaction. */
@JsFun("(video) => { video.play().catch(() => {}); }")
private external fun playSafelyJs(video: HTMLVideoElement)

@JsFun("(video) => { if (video.parentNode) video.parentNode.removeChild(video); }")
private external fun removeElementJs(video: HTMLVideoElement)

@JsFun("(video) => { if (video.requestFullscreen) video.requestFullscreen().catch(() => {}); }")
private external fun requestFullscreenJs(video: HTMLVideoElement)

@JsFun("() => { if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(() => {}); }")
private external fun exitFullscreenJs()

/** Splits an mpv command string using the quoting rules of input.conf. */
internal fun tokenize(command: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var hasToken = false
    for (char in command) {
        when {
            escaped -> {
                current.append(char)
                escaped = false
                hasToken = true
            }

            char == '\\' -> {
                escaped = true
                hasToken = true
            }

            quote != null && char == quote -> quote = null
            quote != null -> {
                current.append(char)
                hasToken = true
            }

            char == '"' || char == '\'' -> {
                quote = char
                hasToken = true
            }

            char.isWhitespace() -> {
                if (hasToken) {
                    result += current.toString()
                    current.clear()
                    hasToken = false
                }
            }

            else -> {
                current.append(char)
                hasToken = true
            }
        }
    }
    if (escaped) current.append('\\')
    if (hasToken) result += current.toString()
    return result
}

internal actual fun createMPVClient(): MPVClient = WebMPVClient(null)

internal actual fun platformClientApiVersion(): Int = (2 shl 16) or 6

internal actual fun platformErrorString(code: Int): String = MPVError.name(code)

internal actual fun platformName(): String = "wasmJs"
