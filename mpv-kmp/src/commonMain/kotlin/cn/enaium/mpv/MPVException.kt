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

/** libmpv error codes (`mpv_error`), re-exported for callers. */
object MPVError {
    const val SUCCESS = 0
    const val EVENT_QUEUE_FULL = -1
    const val NOMEM = -2
    const val UNINITIALIZED = -3
    const val INVALID_PARAMETER = -4
    const val OPTION_NOT_FOUND = -5
    const val OPTION_FORMAT = -6
    const val OPTION_ERROR = -7
    const val PROPERTY_NOT_FOUND = -8
    const val PROPERTY_FORMAT = -9
    const val PROPERTY_UNAVAILABLE = -10
    const val PROPERTY_ERROR = -11
    const val COMMAND = -12
    const val LOADING_FAILED = -13
    const val AO_INIT_FAILED = -14
    const val VO_INIT_FAILED = -15
    const val NOTHING_TO_PLAY = -16
    const val UNKNOWN_FORMAT = -17
    const val UNSUPPORTED = -18
    const val NOT_IMPLEMENTED = -19
    const val GENERIC = -20

    /** Fallback names; platform implementations prefer `mpv_error_string`. */
    fun name(code: Int): String = when (code) {
        SUCCESS -> "success"
        EVENT_QUEUE_FULL -> "event queue full"
        NOMEM -> "out of memory"
        UNINITIALIZED -> "uninitialized"
        INVALID_PARAMETER -> "invalid parameter"
        OPTION_NOT_FOUND -> "option not found"
        OPTION_FORMAT -> "option format"
        OPTION_ERROR -> "option error"
        PROPERTY_NOT_FOUND -> "property not found"
        PROPERTY_FORMAT -> "property format"
        PROPERTY_UNAVAILABLE -> "property unavailable"
        PROPERTY_ERROR -> "property error"
        COMMAND -> "command error"
        LOADING_FAILED -> "loading failed"
        AO_INIT_FAILED -> "audio output init failed"
        VO_INIT_FAILED -> "video output init failed"
        NOTHING_TO_PLAY -> "nothing to play"
        UNKNOWN_FORMAT -> "unknown format"
        UNSUPPORTED -> "unsupported"
        NOT_IMPLEMENTED -> "not implemented"
        GENERIC -> "generic error"
        else -> "error $code"
    }
}

/** Thrown when a libmpv call returns a negative `mpv_error` code. */
class MPVException(
    val errorCode: Int,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: MPVError.name(errorCode), cause) {
    init {
        require(errorCode < 0) { "MPVException requires a negative error code, got $errorCode" }
    }
}

/** Returns the error as a negative code or throws [MPVException]. */
internal fun checkMpvError(code: Int, operation: String? = null) {
    if (code < 0) {
        throw MPVException(code, buildString {
            if (operation != null) append(operation).append(": ")
            append(MPV.errorString(code))
        })
    }
}
