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

package cn.enaium.mpv

/** Pixel formats accepted by the libmpv software render backend. */
enum class MPVVideoFormat(val mpvName: String) {
    /** Four bytes per pixel, memory order R, G, B, unused. */
    RGB0("rgb0"),

    /** Four bytes per pixel, memory order B, G, R, unused. */
    BGR0("bgr0"),
}

/**
 * One RGBA frame produced by [MPVRenderContext.render].
 *
 * [pixels] is owned by the context and reused by the next render call; copy it
 * if the data must outlive the current frame. The byte layout is described by
 * [format] and [stride] (`stride == width * 4` for the current backends).
 */
class MPVVideoFrame(
    val width: Int,
    val height: Int,
    val stride: Int,
    val format: MPVVideoFormat,
    val pixels: ByteArray,
)

/**
 * Software render target for a player.
 *
 * The software backend is deliberately simple: mpv converts/scales the video
 * and writes it into a memory buffer, which the host uploads to a texture.
 * It is slow compared with OpenGL/Metal/Vulkan/D3D11, but works everywhere a
 * libmpv build has the software renderer and is enough for a player UI.
 *
 * Call [update] once per displayed frame; when it returns true, call [render]
 * and upload the returned pixels. Call [reportSwap] after the frame has been
 * presented.
 */
interface MPVRenderContext : AutoCloseable {

    val width: Int

    val height: Int

    val format: MPVVideoFormat

    /** Returns true when a new video frame is available. */
    fun update(): Boolean

    /** Renders the current frame, or null if no frame could be produced. */
    fun render(): MPVVideoFrame?

    /** Tells libmpv that the previously rendered frame has been presented. */
    fun reportSwap()

    override fun close()
}
