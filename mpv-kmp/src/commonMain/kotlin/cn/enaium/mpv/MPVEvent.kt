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

/** All events a client can receive from libmpv, in Kotlin form. */
sealed interface MPVEvent {

    /** The player is shutting down; no further events follow. */
    data object Shutdown : MPVEvent

    data class LogMessage(
        val prefix: String,
        val level: String,
        val text: String,
        val logLevel: Int,
    ) : MPVEvent

    data class GetPropertyReply(
        val replyUserdata: Long,
        val error: Int,
        val name: String,
        val value: MPVNode?,
    ) : MPVEvent

    data class SetPropertyReply(
        val replyUserdata: Long,
        val error: Int,
    ) : MPVEvent

    data class CommandReply(
        val replyUserdata: Long,
        val error: Int,
        val result: MPVNode?,
    ) : MPVEvent

    data class StartFile(val playlistEntryId: Long) : MPVEvent

    data class EndFile(
        val reason: MPVEndFileReason,
        val error: Int,
        val playlistEntryId: Long,
        val playlistInsertId: Long,
        val playlistInsertNumEntries: Int,
    ) : MPVEvent

    data object FileLoaded : MPVEvent

    data object Idle : MPVEvent

    data object Tick : MPVEvent

    data class ClientMessage(val args: List<String>) : MPVEvent

    data object VideoReconfig : MPVEvent

    data object AudioReconfig : MPVEvent

    data object Seek : MPVEvent

    data object PlaybackRestart : MPVEvent

    data class PropertyChange(
        val name: String,
        /** Null when the property became unavailable. */
        val value: MPVNode?,
        val format: MPVFormat,
        /** The id passed to [MPVClient.observeProperty]. */
        val replyUserdata: Long = 0L,
    ) : MPVEvent

    data object QueueOverflow : MPVEvent

    data class Hook(val name: String, val id: Long) : MPVEvent

    /** An event introduced by a newer libmpv than this binding knows about. */
    data class Unknown(
        val eventId: Int,
        val error: Int,
        val replyUserdata: Long,
    ) : MPVEvent
}
