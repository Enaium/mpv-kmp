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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MPVJvmTest {

    private fun headlessPlayer(configure: MPVOptions.() -> Unit = {}): MPVClient =
        MPV.create {
            setOption("config", "no")
            setOption("terminal", "no")
            setOption("idle", "yes")
            setOption("vo", "null")
            setOption("ao", "null")
            configure()
        }

    @Test
    fun lifecycleAndProperties() {
        headlessPlayer().use { player ->
            assertEquals(MPVState.CREATED, player.state)
            assertEquals("jvm", player.capabilities.platform)
            assertTrue(player.capabilities.supports(MPVFeature.RENDER_API))

            player.initialize()
            assertEquals(MPVState.INITIALIZED, player.state)
            assertTrue(player.clientName.isNotEmpty())
            // The runtime libmpv may predate the pinned 0.42 headers; any
            // 2.x client API is ABI-compatible with this binding.
            assertTrue(MPV.clientApiVersion() >= (2 shl 16))
            assertTrue(MPV.errorString(MPVError.PROPERTY_NOT_FOUND).isNotEmpty())

            player.setPropertyDouble(MPVProperty.VOLUME, 42.0)
            assertEquals(42.0, player.getPropertyDouble(MPVProperty.VOLUME) ?: 0.0, 0.5)

            player.setPropertyFlag(MPVProperty.PAUSE, true)
            assertEquals(true, player.getPropertyBoolean(MPVProperty.PAUSE))

            val idle = player.getProperty(MPVProperty.IDLE_ACTIVE)
            assertTrue(idle is MPVNode.Flag)

            player.command("set", MPVProperty.VOLUME, "33")
            assertEquals(33.0, player.getPropertyDouble(MPVProperty.VOLUME) ?: 0.0, 0.5)
        }
    }

    @Test
    fun observesPropertyChanges() = runBlocking {
        headlessPlayer().use { player ->
            val events = Channel<MPVEvent>(Channel.UNLIMITED)
            val collector = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                player.events.collect { events.send(it) }
            }
            try {
                player.initialize()
                player.observeProperty(MPVProperty.VOLUME, replyUserdata = 17L)
                player.setPropertyDouble(MPVProperty.VOLUME, 73.0)

                val change = withTimeout(10_000) {
                    var result: MPVEvent.PropertyChange? = null
                    while (result == null) {
                        val event = events.receive()
                        if (event is MPVEvent.PropertyChange &&
                            event.replyUserdata == 17L &&
                            event.value?.numberOrNull() == 73.0
                        ) {
                            result = event
                        }
                    }
                    result
                }
                assertEquals(MPVProperty.VOLUME, change.name)
                assertEquals(73.0, change.value?.numberOrNull() ?: 0.0, 0.5)
            } finally {
                collector.cancel()
                events.close()
            }
        }
    }

    @Test
    fun propertyReadFromEventCollectorThread(@TempDir tempDir: File): Unit = runBlocking {
        val wav = File(tempDir, "tone.wav")
        writeWav(wav)

        headlessPlayer().use { player ->
            val result = kotlinx.coroutines.CompletableDeferred<String?>()
            val collector = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                player.events.collect { event ->
                    if (event is MPVEvent.FileLoaded) {
                        result.complete(
                            try {
                                player.getPropertyString(MPVProperty.PATH)
                            } catch (e: MPVException) {
                                "error:${e.errorCode}"
                            },
                        )
                    }
                }
            }
            try {
                player.initialize()
                player.command(MPVCommand.LOADFILE, wav.absolutePath)
                val path = withTimeout(20_000) { result.await() }
                assertEquals(wav.absolutePath, path)
            } finally {
                collector.cancel()
            }
        }
    }

    @Test
    fun playsGeneratedWavToEof(@TempDir tempDir: File): Unit = runBlocking {
        val wav = File(tempDir, "tone.wav")
        writeWav(wav)

        headlessPlayer().use { player ->
            val events = Channel<MPVEvent>(Channel.UNLIMITED)
            val collector = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                player.events.collect { events.send(it) }
            }
            try {
                player.initialize()
                player.command(MPVCommand.LOADFILE, wav.absolutePath)

                var duration: Double? = null
                val ended = withTimeout(20_000) {
                    var result: MPVEvent.EndFile? = null
                    while (result == null) {
                        val event = events.receive()
                        if (event is MPVEvent.FileLoaded && duration == null) {
                            // duration can lag FileLoaded by a few milliseconds.
                            duration = withTimeoutOrNull(2_000) {
                                var value: Double? = null
                                while (value == null) {
                                    value = runCatching {
                                        player.getPropertyDouble(MPVProperty.DURATION)
                                    }.getOrNull()
                                    if (value == null) delay(10)
                                }
                                value
                            }
                        }
                        if (event is MPVEvent.EndFile) result = event
                    }
                    result
                }
                assertEquals(MPVEndFileReason.EOF, ended.reason)
                assertEquals(0, ended.error)
                assertEquals(0.4, assertNotNull(duration), 0.1)
            } finally {
                collector.cancel()
                events.close()
            }
        }
    }

    /** 0.4 s, 8 kHz, mono, 16-bit PCM. */
    private fun writeWav(file: File) {
        val sampleRate = 8_000
        val samples = (sampleRate * 0.4).toInt()
        val dataSize = samples * 2
        val bytes = ByteArray(44 + dataSize)
        var offset = 0
        fun ascii(value: String) {
            value.encodeToByteArray().copyInto(bytes, offset)
            offset += value.length
        }

        fun le16(value: Int) {
            bytes[offset++] = (value and 0xFF).toByte()
            bytes[offset++] = ((value shr 8) and 0xFF).toByte()
        }

        fun le32(value: Int) {
            bytes[offset++] = (value and 0xFF).toByte()
            bytes[offset++] = ((value shr 8) and 0xFF).toByte()
            bytes[offset++] = ((value shr 16) and 0xFF).toByte()
            bytes[offset++] = ((value shr 24) and 0xFF).toByte()
        }

        ascii("RIFF")
        le32(36 + dataSize)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)
        le16(1)
        le16(1)
        le32(sampleRate)
        le32(sampleRate * 2)
        le16(2)
        le16(16)
        ascii("data")
        le32(dataSize)
        repeat(samples) { index ->
            val value = (sin(2.0 * PI * 440.0 * index / sampleRate) * 8_000).toInt()
            bytes[offset++] = (value and 0xFF).toByte()
            bytes[offset++] = ((value shr 8) and 0xFF).toByte()
        }
        file.writeBytes(bytes)
    }
}
