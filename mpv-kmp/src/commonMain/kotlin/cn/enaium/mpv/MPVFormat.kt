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
 * Data formats of the libmpv client API (`mpv_format`).
 *
 * [id] is the value of the corresponding `MPV_FORMAT_*` constant.
 */
enum class MPVFormat(val id: Int) {
    NONE(0),
    STRING(1),
    OSD_STRING(2),
    FLAG(3),
    INT64(4),
    DOUBLE(5),
    NODE(6),
    NODE_ARRAY(7),
    NODE_MAP(8),
    BYTE_ARRAY(9);

    companion object {
        fun fromId(id: Int): MPVFormat = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** Reason for a [MPVEvent.EndFile] event (`mpv_end_file_reason`). */
enum class MPVEndFileReason(val id: Int) {
    EOF(0),
    STOP(1),
    QUIT(2),
    ERROR(3),
    REDIRECT(4),
    UNKNOWN(-1);

    companion object {
        fun fromId(id: Int): MPVEndFileReason = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** Event identifiers emitted by libmpv (`mpv_event_id`). */
enum class MPVEventId(val id: Int) {
    NONE(0),
    SHUTDOWN(1),
    LOG_MESSAGE(2),
    GET_PROPERTY_REPLY(3),
    SET_PROPERTY_REPLY(4),
    COMMAND_REPLY(5),
    START_FILE(6),
    END_FILE(7),
    FILE_LOADED(8),
    IDLE(11),
    TICK(14),
    CLIENT_MESSAGE(16),
    VIDEO_RECONFIG(17),
    AUDIO_RECONFIG(18),
    SEEK(20),
    PLAYBACK_RESTART(21),
    PROPERTY_CHANGE(22),
    QUEUE_OVERFLOW(24),
    HOOK(25);

    companion object {
        fun fromId(id: Int): MPVEventId = entries.firstOrNull { it.id == id } ?: NONE
    }
}
