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

/** JVM/Android implementation backed by `libmpv_jni` + libmpv. */
internal class JvmMPVClient(initialHandle: Long) :
    MPVClientBase(MPVCapabilities.native(platformName())) {

    private var handle: Long = initialHandle

    @Volatile
    private var running = true

    private var pumpThread: Thread? = null

    override val clientName: String
        get() {
            requireOpen()
            return Jni.nativeClientName(handle) ?: ""
        }

    override fun setOption(name: String, value: String) {
        requireOpen()
        checkMpvError(Jni.nativeSetOption(handle, name, value), "setOption($name)")
    }

    override fun initialize() {
        requireOpen()
        checkMpvError(Jni.nativeInitialize(handle), "initialize")
        markInitialized()
        startEventPump()
    }

    override fun command(vararg args: String): MPVNode? {
        requireOpen()
        val result = Jni.nativeCommand(handle, arrayOf(*args))
        val error = (result[0] as Number).toInt()
        if (error < 0) {
            throw MPVException(error, "command(${args.joinToString(" ")}): ${MPV.errorString(error)}")
        }
        return result.getOrNull(1).toNodeOrNull()
    }

    override fun commandString(command: String) {
        requireOpen()
        checkMpvError(Jni.nativeCommandString(handle, command), "commandString")
    }

    override fun commandAsync(replyUserdata: Long, vararg args: String) {
        requireOpen()
        checkMpvError(
            Jni.nativeCommandAsync(handle, replyUserdata, arrayOf(*args)),
            "commandAsync",
        )
    }

    override fun getProperty(name: String, format: MPVFormat): MPVNode? {
        requireOpen()
        if (format == MPVFormat.NONE) return null
        val result = Jni.nativeGetProperty(handle, name, format.id)
        val error = (result[0] as Number).toInt()
        if (error < 0) {
            throw MPVException(error, "getProperty($name): ${MPV.errorString(error)}")
        }
        val value = result.getOrNull(1) ?: return null
        // getProperty(STRING/FLAG/...) returns a scalar; wrap it in the node
        // type the caller asked for. NODE formats already return an MPVNode
        // graph via the generic converter.
        return when (format) {
            MPVFormat.STRING, MPVFormat.OSD_STRING -> MPVNode.Text(value as? String ?: "")
            MPVFormat.FLAG -> MPVNode.Flag(value as? Boolean ?: false)
            MPVFormat.INT64 -> MPVNode.Int64((value as? Number)?.toLong() ?: 0L)
            MPVFormat.DOUBLE -> MPVNode.DoubleValue((value as? Number)?.toDouble() ?: 0.0)
            else -> value.toNodeOrNull()
        }
    }

    override fun setProperty(name: String, value: MPVNode) {
        requireOpen()
        checkMpvError(Jni.nativeSetProperty(handle, name, value.toJava()), "setProperty($name)")
    }

    override fun setPropertyAsync(replyUserdata: Long, name: String, value: MPVNode) {
        requireOpen()
        checkMpvError(
            Jni.nativeSetPropertyAsync(handle, replyUserdata, name, value.toJava()),
            "setPropertyAsync($name)",
        )
    }

    override fun observeProperty(name: String, replyUserdata: Long, format: MPVFormat): Long {
        requireOpen()
        checkMpvError(
            Jni.nativeObserveProperty(handle, replyUserdata, name, format.id),
            "observeProperty($name)",
        )
        return replyUserdata
    }

    override fun unobserveProperty(replyUserdata: Long) {
        requireOpen()
        checkMpvError(
            Jni.nativeUnobserveProperty(handle, replyUserdata),
            "unobserveProperty($replyUserdata)",
        )
    }

    override fun requestLogMessages(level: String) {
        requireOpen()
        checkMpvError(Jni.nativeRequestLogMessages(handle, level), "requestLogMessages($level)")
    }

    override fun createRenderContext(
        width: Int,
        height: Int,
        format: MPVVideoFormat,
    ): MPVRenderContext {
        requireInitialized()
        require(width > 0 && height > 0) { "Render target must have a positive size" }
        val result = Jni.nativeCreateRenderContext(handle, width, height, format.mpvName)
        val error = (result[0] as Number).toInt()
        if (error < 0) {
            throw MPVException(
                error,
                "createRenderContext(${width}x$height): ${MPV.errorString(error)}",
            )
        }
        val context = (result.getOrNull(1) as? Number)?.toLong()
            ?: throw MPVException(MPVError.GENERIC, "createRenderContext returned no handle")
        return JvmMPVRenderContext(context, width, height, format)
    }

    override fun stopEventPumpAndDestroy() {
        running = false
        val thread = pumpThread
        if (thread != null) {
            Jni.nativeWakeup(handle)
            thread.join(1_000)
            pumpThread = null
        }
        emit(MPVEvent.Shutdown)
        Jni.nativeTerminateDestroy(handle)
        handle = 0
    }

    private fun startEventPump() {
        if (pumpThread != null) return
        pumpThread = Thread({
            while (running) {
                val raw = try {
                    Jni.nativeWaitEvent(handle, 0.1)
                } catch (t: Throwable) {
                    break
                } ?: continue
                val event = decodeEvent(raw) ?: continue
                emit(event)
                if (event is MPVEvent.Shutdown) break
            }
        }, "mpv-kmp-events").apply {
            isDaemon = true
            start()
        }
    }
}

private fun decodeEvent(raw: Array<Any?>): MPVEvent? {
    val id = (raw[0] as Number).toInt()
    val error = (raw[1] as Number).toInt()
    val replyUserdata = (raw[2] as Number).toLong()
    val payload = raw.getOrNull(3)

    val eventId = MPVEventId.entries.firstOrNull { it.id == id }
        ?: return MPVEvent.Unknown(id, error, replyUserdata)

    return when (eventId) {
        MPVEventId.NONE -> null
        MPVEventId.SHUTDOWN -> MPVEvent.Shutdown
        MPVEventId.LOG_MESSAGE -> {
            val fields = payload as? Array<*> ?: return null
            MPVEvent.LogMessage(
                prefix = fields.getOrNull(0) as? String ?: "",
                level = fields.getOrNull(1) as? String ?: "",
                text = fields.getOrNull(2) as? String ?: "",
                logLevel = (fields.getOrNull(3) as? Number)?.toInt() ?: 0,
            )
        }

        MPVEventId.GET_PROPERTY_REPLY, MPVEventId.PROPERTY_CHANGE -> {
            val fields = payload as? Array<*> ?: return null
            val name = fields.getOrNull(0) as? String ?: ""
            val format = MPVFormat.fromId((fields.getOrNull(1) as? Number)?.toInt() ?: 0)
            val value = fields.getOrNull(2).toNodeOrNull()
            if (eventId == MPVEventId.PROPERTY_CHANGE) {
                MPVEvent.PropertyChange(name, value, format, replyUserdata)
            } else {
                MPVEvent.GetPropertyReply(replyUserdata, error, name, value)
            }
        }

        MPVEventId.SET_PROPERTY_REPLY -> MPVEvent.SetPropertyReply(replyUserdata, error)
        MPVEventId.COMMAND_REPLY ->
            MPVEvent.CommandReply(replyUserdata, error, payload.toNodeOrNull())

        MPVEventId.START_FILE -> MPVEvent.StartFile((payload as? Number)?.toLong() ?: 0L)
        MPVEventId.END_FILE -> {
            val fields = payload as? Array<*> ?: return null
            MPVEvent.EndFile(
                reason = MPVEndFileReason.fromId((fields.getOrNull(0) as? Number)?.toInt() ?: -1),
                error = (fields.getOrNull(1) as? Number)?.toInt() ?: 0,
                playlistEntryId = (fields.getOrNull(2) as? Number)?.toLong() ?: 0L,
                playlistInsertId = (fields.getOrNull(3) as? Number)?.toLong() ?: 0L,
                playlistInsertNumEntries = (fields.getOrNull(4) as? Number)?.toInt() ?: 0,
            )
        }

        MPVEventId.FILE_LOADED -> MPVEvent.FileLoaded
        MPVEventId.IDLE -> MPVEvent.Idle
        MPVEventId.TICK -> MPVEvent.Tick
        MPVEventId.CLIENT_MESSAGE -> {
            val args = (payload as? Array<*>)?.mapNotNull { it as? String } ?: emptyList()
            MPVEvent.ClientMessage(args)
        }

        MPVEventId.VIDEO_RECONFIG -> MPVEvent.VideoReconfig
        MPVEventId.AUDIO_RECONFIG -> MPVEvent.AudioReconfig
        MPVEventId.SEEK -> MPVEvent.Seek
        MPVEventId.PLAYBACK_RESTART -> MPVEvent.PlaybackRestart
        MPVEventId.QUEUE_OVERFLOW -> MPVEvent.QueueOverflow
        MPVEventId.HOOK -> {
            val fields = payload as? Array<*> ?: return null
            MPVEvent.Hook(
                name = fields.getOrNull(0) as? String ?: "",
                id = (fields.getOrNull(1) as? Number)?.toLong() ?: 0L,
            )
        }
    }
}

/** Converts the JNI node representation into the immutable Kotlin tree. */
private fun Any?.toNodeOrNull(): MPVNode? = when (this) {
    null -> null
    is MPVNode -> this
    is String -> MPVNode.Text(this)
    is Boolean -> MPVNode.Flag(this)
    is Byte -> MPVNode.Int64(toLong())
    is Short -> MPVNode.Int64(toLong())
    is Int -> MPVNode.Int64(toLong())
    is Long -> MPVNode.Int64(this)
    is Float -> MPVNode.DoubleValue(toDouble())
    is Double -> MPVNode.DoubleValue(this)
    is ByteArray -> MPVNode.BytesValue(this)
    is Array<*> -> MPVNode.ArrayValue(mapNotNull { it.toNodeOrNull() })
    is Map<*, *> -> {
        val entries = LinkedHashMap<String, MPVNode>(size)
        for ((key, value) in this) {
            entries[key.toString()] = value.toNodeOrNull() ?: MPVNode.None
        }
        MPVNode.MapValue(entries)
    }

    else -> null
}

private fun MPVNode.toJava(): Any? = when (this) {
    is MPVNode.None -> null
    is MPVNode.Text -> value
    is MPVNode.Flag -> value
    is MPVNode.Int64 -> value
    is MPVNode.DoubleValue -> value
    is MPVNode.BytesValue -> value
    is MPVNode.ArrayValue -> {
        val result = arrayOfNulls<Any?>(values.size)
        values.forEachIndexed { index, node -> result[index] = node.toJava() }
        result
    }

    is MPVNode.MapValue -> {
        val result = LinkedHashMap<String, Any?>(entries.size)
        entries.forEach { (key, node) -> result[key] = node.toJava() }
        result
    }
}

internal actual fun createMPVClient(): MPVClient {
    val handle = Jni.nativeCreate()
    if (handle == 0L) throw MPVException(MPVError.NOMEM, "mpv_create() returned null")
    return JvmMPVClient(handle)
}

internal actual fun platformClientApiVersion(): Int = Jni.nativeClientApiVersion().toInt()

internal actual fun platformErrorString(code: Int): String =
    Jni.nativeErrorString(code) ?: MPVError.name(code)

internal actual fun platformName(): String {
    val vm = System.getProperty("java.vm.name").orEmpty()
    val vendor = System.getProperty("java.vendor").orEmpty()
    return if (vm.contains("Dalvik", ignoreCase = true) ||
        vendor.contains("Android", ignoreCase = true)
    ) {
        "android"
    } else {
        "jvm"
    }
}
