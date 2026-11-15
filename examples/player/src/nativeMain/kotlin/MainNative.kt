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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import cn.enaium.mpv.example.player.PlayerApp
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Desktop native entry point.
 *
 * `MPV_KMP_FILE` selects the media, `MPV_KMP_FRAMES` limits the frame count,
 * and `MPV_KMP_NO_AUDIO=1` selects the null audio output for headless runs.
 * The default points at the repository sample when run from the project root.
 */
fun main() {
    val file = getenv("MPV_KMP_FILE")?.toKString()
        ?: "examples/player/src/commonMain/resources/sample.webm"
    val frames = getenv("MPV_KMP_FRAMES")?.toKString()?.toIntOrNull() ?: Int.MAX_VALUE
    val noAudio = getenv("MPV_KMP_NO_AUDIO") != null
    println("mpv-kmp player (native): $file")
    PlayerApp(file, frames, noAudio).run()
}
