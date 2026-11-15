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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MPVCommonTest {

    @Test
    fun formatAndEventIdsMatchTheCPinnedApi() {
        assertEquals(6, MPVFormat.NODE.id)
        assertEquals(9, MPVFormat.BYTE_ARRAY.id)
        assertEquals(MPVFormat.NODE_MAP, MPVFormat.fromId(8))
        assertEquals(1, MPVEventId.SHUTDOWN.id)
        assertEquals(22, MPVEventId.PROPERTY_CHANGE.id)
        assertEquals(MPVEndFileReason.ERROR, MPVEndFileReason.fromId(3))
        assertEquals(MPVEndFileReason.UNKNOWN, MPVEndFileReason.fromId(99))
    }

    @Test
    fun nodeHelpersExposeTypedValues() {
        assertEquals("x", MPVNode.Text("x").stringOrNull())
        assertEquals(42L, MPVNode.Int64(42).longOrNull())
        assertEquals(1.5, MPVNode.DoubleValue(1.5).numberOrNull())
        assertEquals(true, MPVNode.Flag(true).booleanOrNull())
        assertNull(MPVNode.Text("x").numberOrNull())

        val bytes = MPVNode.BytesValue(byteArrayOf(1, 2, 3))
        assertEquals(MPVNode.BytesValue(byteArrayOf(1, 2, 3)), bytes)
        assertEquals(MPVFormat.BYTE_ARRAY, bytes.format)

        val tree = MPVNode.MapValue(
            mapOf(
                "name" to MPVNode.Text("video"),
                "list" to MPVNode.ArrayValue(listOf(MPVNode.Int64(1), MPVNode.Int64(2))),
            ),
        )
        assertEquals(MPVFormat.NODE_MAP, tree.format)
        assertEquals(2, tree.entries.size)
    }

    @Test
    fun errorsUseNegativeMpvCodes() {
        val error = assertFailsWith<MPVException> {
            throw MPVException(MPVError.PROPERTY_UNAVAILABLE)
        }
        assertEquals(-10, error.errorCode)
        assertTrue(error.message!!.contains("property unavailable"))

        assertFailsWith<IllegalArgumentException> {
            MPVException(0)
        }
    }
}
