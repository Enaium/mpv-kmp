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

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.concurrent.Volatile

/** Lifecycle of an [MPVClient]. */
enum class MPVState {
    CREATED,
    INITIALIZED,
    CLOSED,
}

/**
 * A libmpv client (`mpv_handle`) or its web equivalent.
 *
 * Calls are synchronous; libmpv itself requires that the client API is used
 * from one thread at a time unless documented otherwise. Event delivery is
 * asynchronous: [events] emits every event produced by `mpv_wait_event` on a
 * dedicated worker.
 *
 * [close] destroys the handle and is idempotent.
 */
interface MPVClient : AutoCloseable {

    /** The name libmpv assigned to this client, e.g. `mpv` or `mpv/1`. */
    val clientName: String

    /** The `MPV_CLIENT_API_VERSION` of the loaded libmpv (or the pinned value on wasm). */
    val apiVersion: Int get() = MPV.clientApiVersion()

    val state: MPVState

    val isInitialized: Boolean get() = state == MPVState.INITIALIZED

    val isClosed: Boolean get() = state == MPVState.CLOSED

    /** What the current backend actually supports. */
    val capabilities: MPVCapabilities

    /** All events in arrival order. Buffer overflows drop the oldest event. */
    val events: Flow<MPVEvent>

    /**
     * Sets an option before [initialize].
     *
     * @throws MPVException if libmpv rejects the option.
     */
    fun setOption(name: String, value: String)

    /** Initializes the player. Must be called exactly once before any command. */
    fun initialize()

    /**
     * Executes a command synchronously and returns its result, if any.
     *
     * @throws MPVException if the command fails.
     */
    fun command(vararg args: String): MPVNode?

    /**
     * Executes a command passed as one string, using mpv's input.conf parser.
     *
     * @throws MPVException if the command fails.
     */
    fun commandString(command: String)

    /** Queues an asynchronous command; its reply arrives as [MPVEvent.CommandReply]. */
    fun commandAsync(replyUserdata: Long, vararg args: String)

    /**
     * Reads a property in the requested format.
     *
     * @throws MPVException if the property is unavailable or cannot be converted.
     */
    fun getProperty(name: String, format: MPVFormat = MPVFormat.NODE): MPVNode?

    /** Reads a property as a string, or null when it has no value. */
    fun getPropertyString(name: String): String? =
        getProperty(name, MPVFormat.STRING)?.stringOrNull()

    /** Reads a property as a double, or null when it has no value. */
    fun getPropertyDouble(name: String): Double? =
        getProperty(name, MPVFormat.DOUBLE)?.numberOrNull()

    /** Reads a property as a long, or null when it has no value. */
    fun getPropertyLong(name: String): Long? =
        getProperty(name, MPVFormat.INT64)?.longOrNull()

    /** Reads a property as a boolean, or null when it has no value. */
    fun getPropertyBoolean(name: String): Boolean? =
        getProperty(name, MPVFormat.FLAG)?.booleanOrNull()

    /**
     * Sets a property from an [MPVNode], using `MPV_FORMAT_NODE`, which lets
     * libmpv convert between types.
     *
     * @throws MPVException if libmpv rejects the value.
     */
    fun setProperty(name: String, value: MPVNode)

    fun setPropertyString(name: String, value: String) = setProperty(name, MPVNode.Text(value))

    fun setPropertyFlag(name: String, value: Boolean) = setProperty(name, MPVNode.Flag(value))

    /** Alias of [setPropertyFlag], matching [getPropertyBoolean]. */
    fun setPropertyBoolean(name: String, value: Boolean) = setPropertyFlag(name, value)

    fun setPropertyLong(name: String, value: Long) = setProperty(name, MPVNode.Int64(value))

    fun setPropertyDouble(name: String, value: Double) = setProperty(name, MPVNode.DoubleValue(value))

    /** Queues an asynchronous property write; reply arrives as [MPVEvent.SetPropertyReply]. */
    fun setPropertyAsync(replyUserdata: Long, name: String, value: MPVNode)

    /**
     * Registers a property observer. Changes arrive as [MPVEvent.PropertyChange]
     * with the given [replyUserdata] unless it is 0.
     *
     * @return the observer id (the same value as [replyUserdata]).
     */
    fun observeProperty(
        name: String,
        replyUserdata: Long = 0L,
        format: MPVFormat = MPVFormat.NODE,
    ): Long

    /** Removes an observer previously registered with [observeProperty]. */
    fun unobserveProperty(replyUserdata: Long)

    /** Requests log messages; level is one of no, fatal, error, warn, info, v, debug, trace. */
    fun requestLogMessages(level: String)

    /**
     * Creates a software render target of [width] x [height] pixels.
     *
     * Supported by the JVM, Android and Kotlin/Native libmpv backends;
     * the wasm backend has no libmpv render API and reports
     * [MPVError.UNSUPPORTED].
     */
    fun createRenderContext(
        width: Int,
        height: Int,
        format: MPVVideoFormat = MPVVideoFormat.RGB0,
    ): MPVRenderContext = throw MPVException(
        MPVError.UNSUPPORTED,
        "Render contexts are not supported by the ${capabilities.platform} backend",
    )

    /** Destroys the player. Repeated calls are no-ops; using a closed client throws. */
    override fun close()
}

/**
 * Shared implementation of the event stream and lifecycle state.
 *
 * Platform subclasses own the blocking event loop and call [emit] for each
 * event. [close] transitions to [MPVState.CLOSED] and delegates teardown so
 * the platform can stop its event thread before releasing the handle.
 */
internal abstract class MPVClientBase(
    final override val capabilities: MPVCapabilities,
) : MPVClient {

    private val dispatcher = MutableSharedFlow<MPVEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    final override val events: Flow<MPVEvent> = dispatcher.asSharedFlow()

    @Volatile
    private var currentState: MPVState = MPVState.CREATED

    final override val state: MPVState get() = currentState

    protected fun markInitialized() {
        check(currentState == MPVState.CREATED) {
            "MPVClient.initialize() called in state $currentState"
        }
        currentState = MPVState.INITIALIZED
    }

    protected fun requireOpen() {
        check(currentState != MPVState.CLOSED) { "MPVClient is closed" }
    }

    protected fun requireInitialized() {
        requireOpen()
        check(currentState == MPVState.INITIALIZED) {
            "MPVClient must be initialized before this call (state=$currentState)"
        }
    }

    protected fun emit(event: MPVEvent) {
        dispatcher.tryEmit(event)
    }

    final override fun close() {
        if (currentState == MPVState.CLOSED) return
        currentState = MPVState.CLOSED
        stopEventPumpAndDestroy()
    }

    /** Stops the event worker, then destroys the native handle or web element. */
    protected abstract fun stopEventPumpAndDestroy()
}
