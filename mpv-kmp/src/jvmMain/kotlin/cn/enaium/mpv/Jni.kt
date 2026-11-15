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

/**
 * JNI entry points on `cn.enaium.mpv.Jni` (see `jni/mpv_jni.cpp`).
 *
 * Only scalars, strings, arrays and the node object graph cross the boundary;
 * every `mpv_*` struct is marshaled in C++.
 */
internal object Jni {

    init {
        NativeLoader.load()
    }

    external fun nativeCreate(): Long
    external fun nativeInitialize(handle: Long): Int
    external fun nativeTerminateDestroy(handle: Long)
    external fun nativeDestroy(handle: Long)
    external fun nativeSetOption(handle: Long, name: String, value: String): Int
    external fun nativeCommand(handle: Long, args: Array<String>): Array<Any?>
    external fun nativeCommandString(handle: Long, command: String): Int
    external fun nativeCommandAsync(handle: Long, replyUserdata: Long, args: Array<String>): Int
    external fun nativeGetProperty(handle: Long, name: String, format: Int): Array<Any?>
    external fun nativeSetProperty(handle: Long, name: String, value: Any?): Int
    external fun nativeSetPropertyAsync(
        handle: Long,
        replyUserdata: Long,
        name: String,
        value: Any?,
    ): Int

    external fun nativeGetPropertyAsync(
        handle: Long,
        replyUserdata: Long,
        name: String,
        format: Int,
    ): Int

    external fun nativeObserveProperty(
        handle: Long,
        replyUserdata: Long,
        name: String,
        format: Int,
    ): Int

    external fun nativeUnobserveProperty(handle: Long, replyUserdata: Long): Int
    external fun nativeWaitEvent(handle: Long, timeout: Double): Array<Any?>?
    external fun nativeRequestLogMessages(handle: Long, level: String): Int
    external fun nativeClientName(handle: Long): String?
    external fun nativeErrorString(code: Int): String?
    external fun nativeClientApiVersion(): Long
    external fun nativeWakeup(handle: Long)

    external fun nativeCreateRenderContext(
        handle: Long,
        width: Int,
        height: Int,
        format: String,
    ): Array<Any?>

    external fun nativeRenderUpdate(context: Long): Int
    external fun nativeRenderFrame(context: Long, output: ByteArray): Int
    external fun nativeRenderReportSwap(context: Long)
    external fun nativeFreeRenderContext(context: Long)
}
