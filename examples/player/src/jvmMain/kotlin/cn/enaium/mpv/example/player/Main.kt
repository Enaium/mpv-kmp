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

import java.nio.file.Files

/**
 * Desktop entry point.
 *
 * ```text
 * --file <path>    media file or URL (defaults to the bundled sample.webm)
 * --frames <n>     exit after n frames (headless CI)
 * --no-audio       use the null audio output
 * ```
 */
fun main(args: Array<String>) {
    var file: String? = null
    var frames = Int.MAX_VALUE
    var noAudio = false
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--file" -> file = args.getOrNull(++index)
            "--frames" -> frames = args.getOrNull(++index)?.toIntOrNull() ?: Int.MAX_VALUE
            "--no-audio" -> noAudio = true
            else -> if (file == null) file = args[index]
        }
        index++
    }

    val media = file?.takeIf { it.isNotBlank() } ?: extractSample()
    if (media == null) {
        System.err.println("No media file supplied and sample.webm is missing")
        return
    }

    println("mpv-kmp player: $media")
    PlayerApp(media, frames, noAudio).run()
}

/** Copies the classpath sample out so libmpv can open it by path. */
private fun extractSample(): String? {
    val stream = PlayerApp::class.java.getResourceAsStream("/sample.webm") ?: return null
    return try {
        val file = Files.createTempFile("mpv-kmp-sample", ".webm")
        file.toFile().deleteOnExit()
        stream.use { input -> Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        file.toAbsolutePath().toString()
    } finally {
        stream.close()
    }
}
