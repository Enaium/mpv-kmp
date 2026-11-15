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

/** How faithfully a feature is provided on the current backend. */
enum class MPVSupport {
    /** Implemented by libmpv itself. */
    SUPPORTED,

    /** Implemented for the common cases, but with backend-specific limits. */
    PARTIAL,

    /** Simulated by the web backend on top of HTMLMediaElement. */
    EMULATED,

    /** Not available. */
    UNSUPPORTED,
}

/** Feature keys understood by [MPVCapabilities]. */
object MPVFeature {
    const val LOAD_FILE = "load-file"
    const val NETWORK_STREAMS = "network-streams"
    const val AUDIO_OUTPUT = "audio-output"
    const val VIDEO_OUTPUT = "video-output"
    const val SUBTITLES = "subtitles"
    const val HARDWARE_DECODING = "hardware-decoding"
    const val RENDER_API = "render-api"
    const val PROPERTY_OBSERVATION = "property-observation"
    const val ASYNC_COMMANDS = "async-commands"
    const val SCRIPTING = "scripting"
    const val SHADERS = "shaders"
    const val CUSTOM_PROTOCOLS = "custom-protocols"
}

/**
 * Reports which parts of the API are real libmpv behaviour on the current
 * platform. Native and JVM backends report [MPVSupport.SUPPORTED] for
 * everything libmpv exposes; the wasmJs backend reports the HTML media
 * element's actual capabilities.
 */
data class MPVCapabilities(
    val platform: String,
    private val features: Map<String, MPVSupport>,
) {
    fun support(feature: String): MPVSupport = features[feature] ?: MPVSupport.UNSUPPORTED

    fun supports(feature: String): Boolean = support(feature) == MPVSupport.SUPPORTED

    companion object {
        private val ALL_FEATURES = listOf(
            MPVFeature.LOAD_FILE,
            MPVFeature.NETWORK_STREAMS,
            MPVFeature.AUDIO_OUTPUT,
            MPVFeature.VIDEO_OUTPUT,
            MPVFeature.SUBTITLES,
            MPVFeature.HARDWARE_DECODING,
            MPVFeature.RENDER_API,
            MPVFeature.PROPERTY_OBSERVATION,
            MPVFeature.ASYNC_COMMANDS,
            MPVFeature.SCRIPTING,
            MPVFeature.SHADERS,
            MPVFeature.CUSTOM_PROTOCOLS,
        )

        internal fun native(platform: String): MPVCapabilities = MPVCapabilities(
            platform,
            ALL_FEATURES.associateWith { MPVSupport.SUPPORTED },
        )

        /** Defaults for the HTMLMediaElement backend; overridden per feature. */
        internal fun web(): MPVCapabilities = MPVCapabilities(
            "wasmJs",
            mapOf(
                MPVFeature.LOAD_FILE to MPVSupport.SUPPORTED,
                MPVFeature.NETWORK_STREAMS to MPVSupport.PARTIAL,
                MPVFeature.AUDIO_OUTPUT to MPVSupport.SUPPORTED,
                MPVFeature.VIDEO_OUTPUT to MPVSupport.PARTIAL,
                MPVFeature.SUBTITLES to MPVSupport.PARTIAL,
                MPVFeature.HARDWARE_DECODING to MPVSupport.PARTIAL,
                MPVFeature.RENDER_API to MPVSupport.UNSUPPORTED,
                MPVFeature.PROPERTY_OBSERVATION to MPVSupport.EMULATED,
                MPVFeature.ASYNC_COMMANDS to MPVSupport.EMULATED,
                MPVFeature.SCRIPTING to MPVSupport.UNSUPPORTED,
                MPVFeature.SHADERS to MPVSupport.UNSUPPORTED,
                MPVFeature.CUSTOM_PROTOCOLS to MPVSupport.UNSUPPORTED,
            ),
        )
    }
}
