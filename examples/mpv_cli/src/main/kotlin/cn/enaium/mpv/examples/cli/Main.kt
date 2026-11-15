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

package cn.enaium.mpv.examples.cli

import cn.enaium.mpv.MPV
import cn.enaium.mpv.MPVEndFileReason
import cn.enaium.mpv.MPVEvent
import cn.enaium.mpv.MPVProperty
import cn.enaium.mpv.booleanOrNull
import cn.enaium.mpv.numberOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Minimal libmpv CLI:
 *
 *   ./gradlew :examples:mpv_cli:run --args="video.mp4"
 *   ./gradlew :examples:mpv_cli:run --args="--audio-only https://example.com/stream"
 *
 * The JVM backend resolves libmpv at runtime (`brew install mpv`,
 * `apt install libmpv2`, or `MPV_KMP_LIBRARY=/path/to/libmpv.so`).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val audioOnly = args.firstOrNull() == "--audio-only"
    val source = args.drop(if (audioOnly) 1 else 0).firstOrNull()
    if (source == null) {
        System.err.println("usage: mpv_cli [--audio-only] <file-or-url>")
        exitProcess(2)
    }

    MPV.create {
        setOption("config", "no")
        setOption("terminal", "no")
        if (audioOnly) {
            setOption("vo", "null")
        } else {
            setOption("vo", "gpu-next")
            setOption("hwdec", "auto-safe")
        }
    }.use { player ->
        println("libmpv ${MPV.clientApiVersion()} via ${MPV.platform}, client '${player.clientName}'")
        player.initialize()
        player.requestLogMessages("warn")
        player.observeProperty(MPVProperty.TIME_POS, 1L, format = cn.enaium.mpv.MPVFormat.DOUBLE)
        player.observeProperty(MPVProperty.PAUSE, 2L)

        player.command("loadfile", source)

        var lastTick = -1.0
        val events = kotlinx.coroutines.channels.Channel<MPVEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            player.events.collect { events.send(it) }
        }
        try {
            while (true) {
                when (val event = events.receive()) {
                    is MPVEvent.FileLoaded -> {
                        // Properties can already be gone when an output device
                        // fails immediately; the event itself is still valid.
                        val path = runCatching { player.getPropertyString(MPVProperty.PATH) }
                            .getOrNull() ?: source
                        val duration = runCatching { player.getPropertyDouble(MPVProperty.DURATION) }
                            .getOrNull()
                        println("loaded: $path")
                        println("duration: ${duration?.toString() ?: "unavailable"} s")
                    }

                    is MPVEvent.PropertyChange -> when (event.replyUserdata) {
                        1L -> {
                            val position = event.value?.numberOrNull() ?: continue
                            if (position - lastTick >= 1.0 || position == 0.0) {
                                lastTick = position
                                println("position: %.1f s".format(position))
                            }
                        }

                        2L -> println("pause: ${event.value?.booleanOrNull()}")
                        else -> Unit
                    }

                    is MPVEvent.LogMessage -> System.err.print(event.text)
                    is MPVEvent.EndFile -> {
                        println(
                            "ended: ${event.reason}" +
                                if (event.reason == MPVEndFileReason.ERROR) {
                                    " (${MPV.errorString(event.error)})"
                                } else {
                                    ""
                                },
                        )
                        break
                    }

                    MPVEvent.Shutdown -> break
                    else -> Unit
                }
            }
        } finally {
            collector.cancel()
            events.close()
        }
    }
}
