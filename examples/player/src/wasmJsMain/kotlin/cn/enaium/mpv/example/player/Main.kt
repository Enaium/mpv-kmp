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

package cn.enaium.mpv.example.player

import cn.enaium.mpv.MPVWeb
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLVideoElement
import kotlin.JsFun
import kotlin.js.unsafeCast

/**
 * Browser entry point: the page initializes SDL/imgui (Emscripten) and then
 * imports this module. The shared PlayerApp draws the same Dear ImGui UI as
 * the desktop/native/Android builds, while MPVWeb drives an HTML video element
 * positioned over the canvas by [placeVideo].
 */
fun main() {
    val video = document.getElementById("player")!!.unsafeCast<HTMLVideoElement>()
    val client = MPVWeb.create(video)
    PlayerApp(
        initialPath = "sample.webm",
        providedClient = client,
        webVideoSurface = { x, y, width, height, displayWidth, displayHeight, visible ->
            placeVideo(x, y, width, height, displayWidth, displayHeight, visible)
        },
    ).run { step, done ->
        // setTimeout rather than requestAnimationFrame: background/headless
        // tabs throttle RAF to zero frames, while the player still needs to
        // advance after the user starts playback.
        fun tick() {
            if (step()) window.setTimeout({ tick(); null }, 16) else done()
        }
        window.setTimeout({ tick(); null }, 16)
    }
}

/**
 * Places the HTML video over the ImGui video rectangle. ImGui coordinates are
 * logical pixels; the canvas may be CSS-scaled, so both rectangles are used to
 * map them to the current viewport.
 */
@JsFun(
    """(x, y, width, height, displayWidth, displayHeight, visible) => {
        const canvas = document.getElementById('canvas');
        const video = document.getElementById('player');
        if (!canvas || !video) return;
        if (!visible || displayWidth <= 0 || displayHeight <= 0) {
            video.style.display = 'none';
            return;
        }
        const rect = canvas.getBoundingClientRect();
        const scaleX = rect.width / displayWidth;
        const scaleY = rect.height / displayHeight;
        video.style.display = 'block';
        video.style.left = (rect.left + x * scaleX) + 'px';
        video.style.top = (rect.top + y * scaleY) + 'px';
        video.style.width = (width * scaleX) + 'px';
        video.style.height = (height * scaleY) + 'px';
    }""",
)
private external fun placeVideo(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    displayWidth: Float,
    displayHeight: Float,
    visible: Boolean,
)
