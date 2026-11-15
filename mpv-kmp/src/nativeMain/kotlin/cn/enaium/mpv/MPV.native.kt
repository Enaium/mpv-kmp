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

@file:OptIn(ExperimentalForeignApi::class)

package cn.enaium.mpv

import cnames.structs.mpv_handle
import cnames.structs.mpv_render_context
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mpv.*

@OptIn(ExperimentalForeignApi::class)
private class NativeMPVClient(
    handle: CPointer<mpv_handle>,
) : MPVClientBase(MPVCapabilities.native("native")) {

    private var handle: CPointer<mpv_handle>? = handle
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pump: Job? = null

    private fun requireHandle(): CPointer<mpv_handle> =
        handle ?: throw IllegalStateException("MPVClient is closed")

    override val clientName: String
        get() = requireOpen().let { mpv_client_name(requireHandle())?.toKString() ?: "" }

    override fun setOption(name: String, value: String) {
        requireOpen()
        checkMpvError(mpv_set_option_string(requireHandle(), name, value), "setOption($name)")
    }

    override fun initialize() {
        requireOpen()
        checkMpvError(mpv_initialize(requireHandle()), "initialize")
        markInitialized()
        startEventPump()
    }

    override fun command(vararg args: String): MPVNode? {
        requireOpen()
        return memScoped {
            val argsNode = alloc<mpv_node>()
            args.toCStringArrayNode(this, argsNode)
            val result = alloc<mpv_node>()
            val code = mpv_command_node(requireHandle(), argsNode.ptr, result.ptr)
            if (code < 0) throw MPVException(code, "command(${args.joinToString(" ")}): ${MPV.errorString(code)}")
            val parsed = if (result.format == MPV_FORMAT_NONE) null else result.toMPVNode()
            mpv_free_node_contents(result.ptr)
            parsed
        }
    }

    override fun commandString(command: String) {
        requireOpen()
        checkMpvError(mpv_command_string(requireHandle(), command), "commandString")
    }

    override fun commandAsync(replyUserdata: Long, vararg args: String) {
        requireOpen()
        memScoped {
            val argsNode = alloc<mpv_node>()
            args.toCStringArrayNode(this, argsNode)
            checkMpvError(
                mpv_command_node_async(requireHandle(), replyUserdata.toULong(), argsNode.ptr),
                "commandAsync",
            )
        }
    }

    override fun getProperty(name: String, format: MPVFormat): MPVNode? {
        requireOpen()
        return when (format) {
            MPVFormat.NONE -> null
            MPVFormat.NODE, MPVFormat.NODE_ARRAY, MPVFormat.NODE_MAP, MPVFormat.BYTE_ARRAY ->
                memScoped {
                    val node = alloc<mpv_node>()
                    val code = mpv_get_property(requireHandle(), name, MPV_FORMAT_NODE, node.ptr)
                    if (code < 0) throw MPVException(code, "getProperty($name): ${MPV.errorString(code)}")
                    val parsed = node.toMPVNode()
                    mpv_free_node_contents(node.ptr)
                    if (parsed is MPVNode.None) null else parsed
                }

            MPVFormat.STRING, MPVFormat.OSD_STRING -> {
                val raw = if (format == MPVFormat.OSD_STRING) {
                    mpv_get_property_osd_string(requireHandle(), name)
                } else {
                    mpv_get_property_string(requireHandle(), name)
                } ?: throw MPVException(MPVError.PROPERTY_UNAVAILABLE, "getProperty($name)")
                try {
                    MPVNode.Text(raw.toKString())
                } finally {
                    mpv_free(raw)
                }
            }

            MPVFormat.FLAG -> memScoped {
                val value = alloc<IntVar>()
                checkMpvError(
                    mpv_get_property(requireHandle(), name, MPV_FORMAT_FLAG, value.ptr),
                    "getProperty($name)",
                )
                MPVNode.Flag(value.value != 0)
            }

            MPVFormat.INT64 -> memScoped {
                val value = alloc<LongVar>()
                checkMpvError(
                    mpv_get_property(requireHandle(), name, MPV_FORMAT_INT64, value.ptr),
                    "getProperty($name)",
                )
                MPVNode.Int64(value.value)
            }

            MPVFormat.DOUBLE -> memScoped {
                val value = alloc<DoubleVar>()
                checkMpvError(
                    mpv_get_property(requireHandle(), name, MPV_FORMAT_DOUBLE, value.ptr),
                    "getProperty($name)",
                )
                MPVNode.DoubleValue(value.value)
            }
        }
    }

    override fun setProperty(name: String, value: MPVNode) {
        requireOpen()
        memScoped {
            val node = alloc<mpv_node>()
            value.writeNode(node, this)
            checkMpvError(
                mpv_set_property(requireHandle(), name, MPV_FORMAT_NODE, node.ptr),
                "setProperty($name)",
            )
        }
    }

    override fun setPropertyAsync(replyUserdata: Long, name: String, value: MPVNode) {
        requireOpen()
        memScoped {
            val node = alloc<mpv_node>()
            value.writeNode(node, this)
            checkMpvError(
                mpv_set_property_async(
                    requireHandle(),
                    replyUserdata.toULong(),
                    name,
                    MPV_FORMAT_NODE,
                    node.ptr,
                ),
                "setPropertyAsync($name)",
            )
        }
    }

    override fun observeProperty(name: String, replyUserdata: Long, format: MPVFormat): Long {
        requireOpen()
        checkMpvError(
            mpv_observe_property(requireHandle(), replyUserdata.toULong(), name, format.id.toUInt()),
            "observeProperty($name)",
        )
        return replyUserdata
    }

    override fun unobserveProperty(replyUserdata: Long) {
        requireOpen()
        checkMpvError(
            mpv_unobserve_property(requireHandle(), replyUserdata.toULong()),
            "unobserveProperty($replyUserdata)",
        )
    }

    override fun requestLogMessages(level: String) {
        requireOpen()
        checkMpvError(
            mpv_request_log_messages(requireHandle(), level),
            "requestLogMessages($level)",
        )
    }

    override fun createRenderContext(
        width: Int,
        height: Int,
        format: MPVVideoFormat,
    ): MPVRenderContext {
        requireInitialized()
        require(width > 0 && height > 0) { "Render target must have a positive size" }
        return NativeMPVRenderContext(requireHandle(), width, height, format)
    }

    override fun stopEventPumpAndDestroy() {
        // Wake the blocking wait so the pump exits within one poll interval,
        // then join before destroying the handle: mpv_wait_event must never
        // run against a destroyed client.
        handle?.let { mpv_wakeup(it) }
        scope.cancel()
        pump?.let { job -> runBlocking { job.join() } }
        pump = null
        emit(MPVEvent.Shutdown)
        handle?.let { mpv_terminate_destroy(it) }
        handle = null
    }

    private fun startEventPump() {
        if (pump != null) return
        pump = scope.launch {
            while (isActive) {
                val event = try {
                    waitEvent()
                } catch (t: Throwable) {
                    break
                }
                if (event == null) continue
                emit(event)
                if (event is MPVEvent.Shutdown) break
            }
        }
    }

    /** Fetches one event with a bounded timeout and converts it immediately. */
    private fun waitEvent(): MPVEvent? {
        val ctx = requireHandle()
        val raw = mpv_wait_event(ctx, 0.1) ?: return null
        return parseEvent(raw.pointed)
    }

    private fun parseEvent(raw: mpv_event): MPVEvent? {
        val id = raw.event_id.toInt()
        val error = raw.error
        val userdata = raw.reply_userdata.toLong()
        val eventId = MPVEventId.entries.firstOrNull { it.id == id }
            ?: return MPVEvent.Unknown(id, error, userdata)
        return when (eventId) {
            MPVEventId.SHUTDOWN -> MPVEvent.Shutdown
            MPVEventId.LOG_MESSAGE -> {
                val message = raw.data?.reinterpret<mpv_event_log_message>()?.pointed
                MPVEvent.LogMessage(
                    prefix = message?.prefix?.toKString() ?: "",
                    level = message?.level?.toKString() ?: "",
                    text = message?.text?.toKString() ?: "",
                    logLevel = message?.log_level?.toInt() ?: 0,
                )
            }

            MPVEventId.GET_PROPERTY_REPLY -> {
                val property = raw.data?.reinterpret<mpv_event_property>()?.pointed
                MPVEvent.GetPropertyReply(
                    replyUserdata = userdata,
                    error = error,
                    name = property?.name?.toKString() ?: "",
                    value = property?.readPropertyValue(),
                )
            }

            MPVEventId.SET_PROPERTY_REPLY -> MPVEvent.SetPropertyReply(userdata, error)
            MPVEventId.COMMAND_REPLY -> {
                val command = raw.data?.reinterpret<mpv_event_command>()?.pointed
                val result = command?.let {
                    if (it.result.format == MPV_FORMAT_NONE) null else it.result.toMPVNode()
                }
                MPVEvent.CommandReply(userdata, error, result)
            }

            MPVEventId.START_FILE -> {
                val start = raw.data?.reinterpret<mpv_event_start_file>()?.pointed
                MPVEvent.StartFile(start?.playlist_entry_id ?: 0L)
            }

            MPVEventId.END_FILE -> {
                val end = raw.data?.reinterpret<mpv_event_end_file>()?.pointed
                MPVEvent.EndFile(
                    reason = MPVEndFileReason.fromId(end?.reason?.toInt() ?: -1),
                    error = end?.error ?: error,
                    playlistEntryId = end?.playlist_entry_id ?: 0L,
                    playlistInsertId = end?.playlist_insert_id ?: 0L,
                    playlistInsertNumEntries = end?.playlist_insert_num_entries ?: 0,
                )
            }

            MPVEventId.FILE_LOADED -> MPVEvent.FileLoaded
            MPVEventId.IDLE -> MPVEvent.Idle
            MPVEventId.TICK -> MPVEvent.Tick
            MPVEventId.CLIENT_MESSAGE -> {
                val message = raw.data?.reinterpret<mpv_event_client_message>()?.pointed
                val count = message?.num_args ?: 0
                val args = if (message == null || count <= 0) {
                    emptyList()
                } else {
                    val ptr = message.args
                    (0 until count).mapNotNull { index ->
                        ptr?.get(index)?.toKString()
                    }
                }
                MPVEvent.ClientMessage(args)
            }

            MPVEventId.VIDEO_RECONFIG -> MPVEvent.VideoReconfig
            MPVEventId.AUDIO_RECONFIG -> MPVEvent.AudioReconfig
            MPVEventId.SEEK -> MPVEvent.Seek
            MPVEventId.PLAYBACK_RESTART -> MPVEvent.PlaybackRestart
            MPVEventId.PROPERTY_CHANGE -> {
                val property = raw.data?.reinterpret<mpv_event_property>()?.pointed
                val format = MPVFormat.fromId(property?.format?.toInt() ?: 0)
                MPVEvent.PropertyChange(
                    name = property?.name?.toKString() ?: "",
                    value = property?.readPropertyValue(),
                    format = format,
                    replyUserdata = userdata,
                )
            }

            MPVEventId.QUEUE_OVERFLOW -> MPVEvent.QueueOverflow
            MPVEventId.HOOK -> {
                val hook = raw.data?.reinterpret<mpv_event_hook>()?.pointed
                MPVEvent.Hook(hook?.name?.toKString() ?: "", hook?.id?.toLong() ?: 0L)
            }

            MPVEventId.NONE -> return null
        }
    }
}

/**
 * Reads the property payload of an event. The data belongs to libmpv and must
 * not be freed; the node is copied into Kotlin values.
 */
private fun mpv_event_property.readPropertyValue(): MPVNode? {
    val fmt = MPVFormat.fromId(format.toInt())
    val ptr = data ?: return null
    return when (fmt) {
        MPVFormat.NONE -> null
        MPVFormat.NODE, MPVFormat.NODE_ARRAY, MPVFormat.NODE_MAP, MPVFormat.BYTE_ARRAY ->
            ptr.reinterpret<mpv_node>().pointed.toMPVNode()

        MPVFormat.STRING, MPVFormat.OSD_STRING ->
            ptr.reinterpret<CPointerVar<ByteVar>>().pointed.value?.toKString()?.let(MPVNode::Text)

        MPVFormat.FLAG -> MPVNode.Flag(ptr.reinterpret<IntVar>().pointed.value != 0)
        MPVFormat.INT64 -> MPVNode.Int64(ptr.reinterpret<LongVar>().pointed.value)
        MPVFormat.DOUBLE -> MPVNode.DoubleValue(ptr.reinterpret<DoubleVar>().pointed.value)
    }
}

private fun mpv_node.toMPVNode(): MPVNode = when (MPVFormat.fromId(format.toInt())) {
    MPVFormat.NONE -> MPVNode.None
    MPVFormat.STRING, MPVFormat.OSD_STRING -> MPVNode.Text(u.string?.toKString() ?: "")
    MPVFormat.FLAG -> MPVNode.Flag(u.flag != 0)
    MPVFormat.INT64 -> MPVNode.Int64(u.int64)
    MPVFormat.DOUBLE -> MPVNode.DoubleValue(u.double_)
    MPVFormat.NODE_ARRAY -> {
        val list = u.list?.pointed
        val count = list?.num ?: 0
        val values = list?.values
        MPVNode.ArrayValue(
            if (values == null || count <= 0) emptyList()
            else (0 until count).map { values[it].toMPVNode() },
        )
    }

    MPVFormat.NODE_MAP -> {
        val list = u.list?.pointed
        val count = list?.num ?: 0
        val values = list?.values
        val keys = list?.keys
        val entries = linkedMapOf<String, MPVNode>()
        if (values != null && keys != null) {
            for (index in 0 until count) {
                val key = keys[index]?.toKString() ?: continue
                entries[key] = values[index].toMPVNode()
            }
        }
        MPVNode.MapValue(entries)
    }

    MPVFormat.BYTE_ARRAY -> {
        val array = u.ba?.pointed
        val size = array?.size?.toInt() ?: 0
        val data = array?.data?.reinterpret<ByteVar>()
        MPVNode.BytesValue(if (data == null || size <= 0) ByteArray(0) else data.readBytes(size))
    }

    MPVFormat.NODE -> MPVNode.None
}

/** Writes [this] tree into a caller-allocated [mpv_node]. */
private fun MPVNode.writeNode(dest: mpv_node, scope: MemScope) {
    when (this) {
        is MPVNode.None -> dest.format = MPV_FORMAT_NONE
        is MPVNode.Text -> {
            dest.format = MPV_FORMAT_STRING
            dest.u.string = value.toCString(scope)
        }

        is MPVNode.Flag -> {
            dest.format = MPV_FORMAT_FLAG
            dest.u.flag = if (value) 1 else 0
        }

        is MPVNode.Int64 -> {
            dest.format = MPV_FORMAT_INT64
            dest.u.int64 = value
        }

        is MPVNode.DoubleValue -> {
            dest.format = MPV_FORMAT_DOUBLE
            dest.u.double_ = value
        }

        is MPVNode.ArrayValue -> {
            dest.format = MPV_FORMAT_NODE_ARRAY
            val list = scope.alloc<mpv_node_list>()
            val values = scope.allocArray<mpv_node>(this.values.size)
            this.values.forEachIndexed { index, node -> node.writeNode(values[index], scope) }
            list.num = this.values.size
            list.values = values
            list.keys = null
            dest.u.list = list.ptr
        }

        is MPVNode.MapValue -> {
            dest.format = MPV_FORMAT_NODE_MAP
            val list = scope.alloc<mpv_node_list>()
            val values = scope.allocArray<mpv_node>(entries.size)
            val keys = scope.allocArray<CPointerVar<ByteVar>>(entries.size)
            entries.entries.forEachIndexed { index, (key, node) ->
                keys[index] = key.toCString(scope)
                node.writeNode(values[index], scope)
            }
            list.num = entries.size
            list.values = values
            list.keys = keys
            dest.u.list = list.ptr
        }

        is MPVNode.BytesValue -> {
            dest.format = MPV_FORMAT_BYTE_ARRAY
            val array = scope.alloc<mpv_byte_array>()
            val bytes = scope.allocArray<ByteVar>(value.size)
            value.forEachIndexed { index, byte -> bytes[index] = byte }
            array.data = bytes
            array.size = value.size.toULong().convert()
            dest.u.ba = array.ptr
        }
    }
}

/** Allocates a NUL-terminated UTF-8 copy in [scope]. */
private fun String.toCString(scope: MemScope): CPointer<ByteVar> {
    val bytes = encodeToByteArray()
    val buffer = scope.allocArray<ByteVar>(bytes.size + 1)
    bytes.forEachIndexed { index, byte -> buffer[index] = byte }
    buffer[bytes.size] = 0
    return buffer
}

private fun Array<out String>.toCStringArrayNode(scope: MemScope, dest: mpv_node) {
    dest.format = MPV_FORMAT_NODE_ARRAY
    val list = scope.alloc<mpv_node_list>()
    val values = scope.allocArray<mpv_node>(size)
    forEachIndexed { index, argument ->
        values[index].format = MPV_FORMAT_STRING
        values[index].u.string = argument.toCString(scope)
    }
    list.num = size
    list.values = values
    list.keys = null
    dest.u.list = list.ptr
}

/** Kotlin/Native implementation of libmpv's software render target. */
@OptIn(ExperimentalForeignApi::class)
private class NativeMPVRenderContext(
    handle: CPointer<mpv_handle>,
    override val width: Int,
    override val height: Int,
    override val format: MPVVideoFormat,
) : MPVRenderContext {

    private var context: CPointer<mpv_render_context>? = memScoped {
        val params = allocArray<mpv_render_param>(2)
        params[0].type = MPV_RENDER_PARAM_API_TYPE
        params[0].data = MPV_RENDER_API_TYPE_SW.toCString(this)
        params[1].type = 0u
        params[1].data = null

        val result = alloc<CPointerVar<mpv_render_context>>()
        val error = mpv_render_context_create(result.ptr, handle, params)
        if (error < 0) throw MPVException(error, "mpv_render_context_create: ${MPV.errorString(error)}")
        result.value
    }

    private val byteCount = width * height * 4
    private val buffer = nativeHeap.allocArray<ByteVar>(byteCount)

    override fun update(): Boolean {
        val ctx = context ?: return false
        return (mpv_render_context_update(ctx).toInt() and MPV_RENDER_UPDATE_FRAME) != 0
    }

    override fun render(): MPVVideoFrame? {
        val ctx = context ?: return null
        return memScoped {
            val size = allocArray<IntVar>(2)
            size[0] = width
            size[1] = height
            val stride = alloc<ULongVar>()
            stride.value = (width * 4).toULong()
            val blockForTargetTime = alloc<IntVar>()
            blockForTargetTime.value = 1

            val params = allocArray<mpv_render_param>(6)
            params[0].type = MPV_RENDER_PARAM_SW_SIZE
            params[0].data = size
            params[1].type = MPV_RENDER_PARAM_SW_FORMAT
            params[1].data = format.mpvName.toCString(this)
            params[2].type = MPV_RENDER_PARAM_SW_STRIDE
            params[2].data = stride.ptr
            params[3].type = MPV_RENDER_PARAM_SW_POINTER
            params[3].data = buffer
            params[4].type = MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME
            params[4].data = blockForTargetTime.ptr
            params[5].type = 0u
            params[5].data = null

            val error = mpv_render_context_render(ctx, params)
            if (error < 0) return@memScoped null
            MPVVideoFrame(
                width = width,
                height = height,
                stride = width * 4,
                format = format,
                pixels = buffer.readBytes(byteCount),
            )
        }
    }

    override fun reportSwap() {
        context?.let { mpv_render_context_report_swap(it) }
    }

    override fun close() {
        val ctx = context ?: return
        context = null
        mpv_render_context_free(ctx)
        nativeHeap.free(buffer)
    }

    private companion object {
        const val MPV_RENDER_UPDATE_FRAME = 1
    }
}

internal actual fun createMPVClient(): MPVClient {
    val handle = mpv_create() ?: throw MPVException(MPVError.NOMEM, "mpv_create() returned null")
    return NativeMPVClient(handle)
}

internal actual fun platformClientApiVersion(): Int = mpv_client_api_version().toInt()

internal actual fun platformErrorString(code: Int): String =
    mpv_error_string(code)?.toKString() ?: MPVError.name(code)

internal actual fun platformName(): String = "native"
