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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MPVNativeTest {

    private fun headlessPlayer(): MPVClient = MPV.create {
        setOption("config", "no")
        setOption("terminal", "no")
        setOption("idle", "yes")
        setOption("vo", "null")
        setOption("ao", "null")
    }

    @Test
    fun lifecycleAndProperties() {
        val player = headlessPlayer()
        try {
            player.initialize()
            assertEquals(MPVState.INITIALIZED, player.state)
            assertEquals("native", player.capabilities.platform)
            assertTrue(player.clientName.isNotEmpty())
            // The host libmpv may predate the pinned 0.42 headers.
            assertTrue(MPV.clientApiVersion() >= (2 shl 16))

            player.setPropertyDouble(MPVProperty.VOLUME, 55.0)
            assertEquals(55.0, player.getPropertyDouble(MPVProperty.VOLUME) ?: 0.0, 0.5)

            player.setPropertyFlag(MPVProperty.PAUSE, true)
            assertEquals(true, player.getPropertyBoolean(MPVProperty.PAUSE))

            val volume = player.getProperty(MPVProperty.VOLUME)
            assertTrue(volume is MPVNode.DoubleValue)

            player.command(MPVCommand.SET, MPVProperty.VOLUME, "44")
            assertEquals(44.0, player.getPropertyDouble(MPVProperty.VOLUME) ?: 0.0, 0.5)
        } finally {
            player.close()
        }
    }

    @Test
    fun observesPropertyChanges() = runBlocking {
        val player = headlessPlayer()
        val change = CompletableDeferred<MPVEvent.PropertyChange>()
        val collector = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            player.events.collect { event ->
                if (event is MPVEvent.PropertyChange &&
                    event.replyUserdata == 42L &&
                    event.value?.numberOrNull() == 66.0
                ) {
                    change.complete(event)
                }
            }
        }
        try {
            player.initialize()
            player.observeProperty(MPVProperty.VOLUME, replyUserdata = 42L)
            player.setPropertyDouble(MPVProperty.VOLUME, 66.0)

            val event = withTimeout(10_000) { change.await() }
            assertEquals(MPVProperty.VOLUME, event.name)
            assertEquals(66.0, event.value?.numberOrNull() ?: 0.0, 0.5)
        } finally {
            collector.cancel()
            player.close()
        }
    }
}
