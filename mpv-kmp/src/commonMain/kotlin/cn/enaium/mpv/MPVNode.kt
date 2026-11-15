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
 * An immutable copy of `mpv_node`.
 *
 * libmpv's node tree is only valid until the next event is fetched or the
 * owning property call returns, so every platform implementation converts it
 * into this tree immediately.
 */
sealed interface MPVNode {

    /** The format of this value, matching one of the `MPV_FORMAT_*` constants. */
    val format: MPVFormat

    data object None : MPVNode {
        override val format: MPVFormat get() = MPVFormat.NONE
    }

    data class Text(val value: String) : MPVNode {
        override val format: MPVFormat get() = MPVFormat.STRING
    }

    data class Flag(val value: Boolean) : MPVNode {
        override val format: MPVFormat get() = MPVFormat.FLAG
    }

    data class Int64(val value: Long) : MPVNode {
        override val format: MPVFormat get() = MPVFormat.INT64
    }

    data class DoubleValue(val value: Double) : MPVNode {
        override val format: MPVFormat get() = MPVFormat.DOUBLE
    }

    data class ArrayValue(val values: List<MPVNode>) : MPVNode {
        override val format: MPVFormat get() = MPVFormat.NODE_ARRAY
    }

    data class MapValue(val entries: Map<String, MPVNode>) : MPVNode {
        override val format: MPVFormat get() = MPVFormat.NODE_MAP
    }

    class BytesValue(val value: ByteArray) : MPVNode {
        override val format: MPVFormat get() = MPVFormat.BYTE_ARRAY

        override fun equals(other: Any?): Boolean =
            this === other || (other is BytesValue && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "BytesValue(${value.size} bytes)"
    }

    companion object {
        /** Maps a raw `MPV_FORMAT_*` value plus its payload to an [MPVNode]. */
        fun of(format: MPVFormat, value: Any?): MPVNode = when (format) {
            MPVFormat.NONE -> None
            MPVFormat.STRING, MPVFormat.OSD_STRING -> Text(value as? String ?: "")
            MPVFormat.FLAG -> Flag(value as? Boolean ?: ((value as? Number)?.toInt() ?: 0) != 0)
            MPVFormat.INT64 -> Int64((value as? Number)?.toLong() ?: 0L)
            MPVFormat.DOUBLE -> DoubleValue((value as? Number)?.toDouble() ?: 0.0)
            MPVFormat.BYTE_ARRAY -> BytesValue(value as? ByteArray ?: ByteArray(0))
            MPVFormat.NODE -> value as? MPVNode ?: None
            MPVFormat.NODE_ARRAY -> value as? MPVNode ?: None
            MPVFormat.NODE_MAP -> value as? MPVNode ?: None
        }
    }
}

/** The string payload, or null for non-string nodes. */
fun MPVNode.stringOrNull(): String? = (this as? MPVNode.Text)?.value

/** The numeric payload as a double, covering INT64 and DOUBLE nodes. */
fun MPVNode.numberOrNull(): Double? = when (this) {
    is MPVNode.DoubleValue -> value
    is MPVNode.Int64 -> value.toDouble()
    else -> null
}

/** The numeric payload as a long, covering INT64 and DOUBLE nodes. */
fun MPVNode.longOrNull(): Long? = when (this) {
    is MPVNode.Int64 -> value
    is MPVNode.DoubleValue -> value.toLong()
    else -> null
}

/** The boolean payload, or null for other nodes. */
fun MPVNode.booleanOrNull(): Boolean? = (this as? MPVNode.Flag)?.value
