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
 * Entry point for creating libmpv clients.
 *
 * ```kotlin
 * val player = MPV.create {
 *     setOption("vo", "libmpv")
 *     setOption("hwdec", "auto")
 * }
 * player.initialize()
 * player.events.collect { event -> ... }
 * ```
 */
object MPV {
    /** The pinned libmpv release (mpv submodule). */
    const val VERSION: String = "0.42.0"

    /** The string form of `MPV_CLIENT_API_VERSION` in 0.42.0. */
    const val CLIENT_API_VERSION: String = "2.6"

    /** Creates an uninitialized client. */
    fun create(): MPVClient = createMPVClient()

    /** Creates a client and applies [configure] before initialization. */
    fun create(configure: MPVOptions.() -> Unit): MPVClient =
        create().also { MPVOptions(it).configure() }

    /** The `MPV_CLIENT_API_VERSION` of the loaded libmpv. */
    fun clientApiVersion(): Int = platformClientApiVersion()

    /** Human-readable text for a negative `mpv_error` code. */
    fun errorString(code: Int): String = platformErrorString(code)

    /** Backend identifier: `jvm`, `android`, `native` or `wasmJs`. */
    val platform: String get() = platformName()
}

/** Sets initialization options on a client. */
class MPVOptions internal constructor(private val client: MPVClient) {

    fun setOption(name: String, value: String) {
        client.setOption(name, value)
    }

    fun setOption(name: String, value: Boolean) {
        client.setOption(name, if (value) "yes" else "no")
    }

    fun setOption(name: String, value: Number) {
        client.setOption(name, value.toString())
    }

    operator fun set(name: String, value: String) = setOption(name, value)

    operator fun set(name: String, value: Boolean) = setOption(name, value)

    operator fun set(name: String, value: Number) = setOption(name, value)
}

internal expect fun createMPVClient(): MPVClient

internal expect fun platformClientApiVersion(): Int

internal expect fun platformErrorString(code: Int): String

internal expect fun platformName(): String
