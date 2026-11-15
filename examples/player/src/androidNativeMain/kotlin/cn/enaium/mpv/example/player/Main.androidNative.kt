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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package cn.enaium.mpv.example.player

import cn.enaium.sdl.SDL
import kotlin.native.CName
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import sdlscale.SDL_GetDisplayContentScale

/**
 * Android native entry point. SDLActivity (from sdl-kmp-android-jvm) loads
 * `libmain.so` and calls this exported SDL_main on SDL's dedicated thread.
 * MainActivity copies sample.webm into the app's files directory before the
 * SDL thread starts; a different path/URL can be entered in the player UI.
 */
@CName("SDL_main")
fun sdlMain(argc: Int, argv: CPointer<CPointerVar<ByteVar>>?): Int {
    PlayerApp(
        initialPath = "/data/data/cn.enaium.mpv.example.player.ndk/files/sample.webm",
        noAudio = false,
        uiScaleProvider = { window ->
            SDL_GetDisplayContentScale(window.displayId.toUInt())
        },
    ).run()
    SDL.quit()
    return 0
}
