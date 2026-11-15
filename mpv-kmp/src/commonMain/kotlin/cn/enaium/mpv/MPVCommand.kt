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

/** Frequently used libmpv command names. */
object MPVCommand {
    const val LOADFILE = "loadfile"
    const val STOP = "stop"
    const val SEEK = "seek"
    const val SET = "set"
    const val CYCLE = "cycle"
    const val QUIT = "quit"
    const val SCREENSHOT = "screenshot"
    const val OSD_MESSAGE = "show-text"
}

/** Frequently used libmpv property names. */
object MPVProperty {
    const val PAUSE = "pause"
    const val TIME_POS = "time-pos"
    const val TIME_REMAINING = "time-remaining"
    const val DURATION = "duration"
    const val VOLUME = "volume"
    const val MUTE = "mute"
    const val SPEED = "speed"
    const val LOOP_FILE = "loop-file"
    const val LOOP_PLAYLIST = "loop-playlist"
    const val MEDIA_TITLE = "media-title"
    const val PATH = "path"
    const val FILENAME = "filename"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val FULLSCREEN = "fullscreen"
    const val SEEKABLE = "seekable"
    const val EOF_REACHED = "eof-reached"
    const val IDLE_ACTIVE = "idle-active"
    const val PAUSE_FOR_CACHE = "pause-for-cache"
    const val VIDEO_FORMAT = "video-format"
    const val AUDIO_CODEC = "audio-codec-name"
    const val CHAPTER = "chapter"
    const val CHAPTERS = "chapters"
    const val TRACK_LIST = "track-list"
    const val PLAYLIST = "playlist"
    const val METADATA = "metadata"
    const val SUB_VISIBILITY = "sub-visibility"
    const val SUB_TEXT = "sub-text"
    const val OSD_DIMENSIONS = "osd-dimensions"
}
