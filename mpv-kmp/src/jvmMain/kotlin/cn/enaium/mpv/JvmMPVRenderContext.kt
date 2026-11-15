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

/** JVM implementation of the libmpv software render target. */
internal class JvmMPVRenderContext(
    private var handle: Long,
    override val width: Int,
    override val height: Int,
    override val format: MPVVideoFormat,
) : MPVRenderContext {

    private val pixels = ByteArray(width * height * 4)

    override fun update(): Boolean {
        if (handle == 0L) return false
        return (Jni.nativeRenderUpdate(handle) and MPV_RENDER_UPDATE_FRAME) != 0
    }

    override fun render(): MPVVideoFrame? {
        if (handle == 0L) return null
        val error = Jni.nativeRenderFrame(handle, pixels)
        if (error < 0) return null
        return MPVVideoFrame(width, height, width * 4, format, pixels)
    }

    override fun reportSwap() {
        if (handle != 0L) Jni.nativeRenderReportSwap(handle)
    }

    override fun close() {
        if (handle == 0L) return
        Jni.nativeFreeRenderContext(handle)
        handle = 0
    }

    private companion object {
        const val MPV_RENDER_UPDATE_FRAME = 1
    }
}
