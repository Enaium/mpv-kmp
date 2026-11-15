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

@file:OptIn(ExperimentalWasmJsInterop::class)

package cn.enaium.mpv

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsExport
import kotlin.js.JsReference
import kotlin.js.get
import kotlin.js.toJsReference
import kotlin.js.unsafeCast
import org.w3c.dom.HTMLVideoElement

/**
 * JavaScript facade for the wasmJs backend. The Kotlin/Wasm npm package
 * exports these package-level functions; examples and browser apps can use
 * them without writing Kotlin.
 */

@JsExport
fun createPlayer(): JsReference<Any> = MPVWeb.create().toJsReference().toOpaque()

@JsExport
fun createPlayerFor(video: JsAny): JsReference<Any> =
    MPVWeb.create(video.unsafeCast<HTMLVideoElement>()).toJsReference().toOpaque()

@JsExport
fun initialize(player: JsReference<Any>) {
    player.client().initialize()
}

@JsExport
fun command(player: JsReference<Any>, commandLine: String) {
    player.client().commandString(commandLine)
}

@JsExport
fun getString(player: JsReference<Any>, name: String): String? =
    player.client().getPropertyString(name)

@JsExport
fun getNumber(player: JsReference<Any>, name: String): Double? =
    player.client().getPropertyDouble(name)

@JsExport
fun getFlag(player: JsReference<Any>, name: String): Boolean? =
    player.client().getPropertyBoolean(name)

@JsExport
fun setString(player: JsReference<Any>, name: String, value: String) {
    player.client().setPropertyString(name, value)
}

@JsExport
fun setNumber(player: JsReference<Any>, name: String, value: Double) {
    player.client().setPropertyDouble(name, value)
}

@JsExport
fun setFlag(player: JsReference<Any>, name: String, value: Boolean) {
    player.client().setPropertyFlag(name, value)
}

@JsExport
fun close(player: JsReference<Any>) {
    player.client().close()
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> JsReference<T>.toOpaque(): JsReference<Any> = this as JsReference<Any>

private fun JsReference<Any>.client(): MPVClient = get() as MPVClient

fun main() = Unit
