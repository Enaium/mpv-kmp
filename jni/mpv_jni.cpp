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

// JNI bridge for libmpv.
//
// The bridge dlopen()s libmpv instead of linking it. Reasons:
//   * the desktop JNI artifacts stay small and buildable on any host;
//   * libmpv's dependency tree (FFmpeg, libass, libplacebo, ...) is the
//     consumer's package manager's job;
//   * a missing or incompatible libmpv produces one clear error instead of a
//     process-level loader failure.
//
// All values crossing the boundary use the pinned `mpv/client.h` ABI. Nodes
// are converted recursively: String/Boolean/Long/Double, Object[] for arrays,
// LinkedHashMap for maps and byte[] for byte arrays.

#include <jni.h>
#include <mpv/client.h>
#include <mpv/render.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace {

// ---------------------------------------------------------------------------
// Dynamic libmpv loader
// ---------------------------------------------------------------------------

struct MpvApi {
    void *library = nullptr;

    decltype(&mpv_create) create = nullptr;
    decltype(&mpv_initialize) initialize = nullptr;
    decltype(&mpv_terminate_destroy) terminate_destroy = nullptr;
    decltype(&mpv_destroy) destroy = nullptr;
    decltype(&mpv_set_option_string) set_option_string = nullptr;
    decltype(&mpv_command_node) command_node = nullptr;
    decltype(&mpv_command_node_async) command_node_async = nullptr;
    decltype(&mpv_command_string) command_string = nullptr;
    decltype(&mpv_get_property) get_property = nullptr;
    decltype(&mpv_get_property_string) get_property_string = nullptr;
    decltype(&mpv_get_property_osd_string) get_property_osd_string = nullptr;
    decltype(&mpv_set_property) set_property = nullptr;
    decltype(&mpv_set_property_async) set_property_async = nullptr;
    decltype(&mpv_get_property_async) get_property_async = nullptr;
    decltype(&mpv_observe_property) observe_property = nullptr;
    decltype(&mpv_unobserve_property) unobserve_property = nullptr;
    decltype(&mpv_wait_event) wait_event = nullptr;
    decltype(&mpv_request_log_messages) request_log_messages = nullptr;
    decltype(&mpv_client_name) client_name = nullptr;
    decltype(&mpv_error_string) error_string = nullptr;
    decltype(&mpv_client_api_version) client_api_version = nullptr;
    decltype(&mpv_free) free_ = nullptr;
    decltype(&mpv_free_node_contents) free_node_contents = nullptr;
    decltype(&mpv_wakeup) wakeup = nullptr;

    // Optional: a libmpv build without the render API still supports the
    // client API, so these are resolved without failing the whole loader.
    decltype(&mpv_render_context_create) render_context_create = nullptr;
    decltype(&mpv_render_context_free) render_context_free = nullptr;
    decltype(&mpv_render_context_render) render_context_render = nullptr;
    decltype(&mpv_render_context_update) render_context_update = nullptr;
    decltype(&mpv_render_context_report_swap) render_context_report_swap = nullptr;
};

MpvApi g_api;
std::mutex g_apiMutex;
std::string g_loadError;

void *openLibrary(const char *name) {
#ifdef _WIN32
    return reinterpret_cast<void *>(LoadLibraryA(name));
#else
    return dlopen(name, RTLD_NOW | RTLD_GLOBAL);
#endif
}

std::string loaderError() {
#ifdef _WIN32
    return "LoadLibrary failed (error " + std::to_string(GetLastError()) + ")";
#else
    const char *error = dlerror();
    return error ? error : "dlopen failed";
#endif
}

// ---------------------------------------------------------------------------
// UTF-8 <-> UTF-16 (JNI's NewStringUTF/GetStringUTFChars use modified UTF-8,
// which mangles supplementary characters such as emoji and is not what libmpv
// emits or expects).
// ---------------------------------------------------------------------------

std::string utf16ToUtf8(const jchar *chars, jsize length) {
    std::string out;
    out.reserve(static_cast<size_t>(length) * 2);
    for (jsize i = 0; i < length; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < length) {
            uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        if (c < 0x80) {
            out.push_back(static_cast<char>(c));
        } else if (c < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (c >> 6)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else if (c < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (c >> 12)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (c >> 18)));
            out.push_back(static_cast<char>(0x80 | ((c >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        }
    }
    return out;
}

std::u16string utf8ToUtf16(const char *input) {
    std::u16string out;
    if (!input) return out;
    auto *s = reinterpret_cast<const unsigned char *>(input);
    while (*s) {
        uint32_t c;
        if (s[0] < 0x80) {
            c = s[0];
            s += 1;
        } else if ((s[0] & 0xE0) == 0xC0 && (s[1] & 0xC0) == 0x80) {
            c = ((s[0] & 0x1F) << 6) | (s[1] & 0x3F);
            s += 2;
        } else if ((s[0] & 0xF0) == 0xE0 && (s[1] & 0xC0) == 0x80 && (s[2] & 0xC0) == 0x80) {
            c = ((s[0] & 0x0F) << 12) | ((s[1] & 0x3F) << 6) | (s[2] & 0x3F);
            s += 3;
        } else if ((s[0] & 0xF8) == 0xF0 && (s[1] & 0xC0) == 0x80 &&
                   (s[2] & 0xC0) == 0x80 && (s[3] & 0xC0) == 0x80) {
            c = ((s[0] & 0x07) << 18) | ((s[1] & 0x3F) << 12) |
                ((s[2] & 0x3F) << 6) | (s[3] & 0x3F);
            s += 4;
        } else {
            c = 0xFFFD;
            s += 1;
        }
        if (c > 0xFFFF) {
            c -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (c >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (c & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(c));
        }
    }
    return out;
}

jstring newStringUtf8(JNIEnv *env, const char *value) {
    if (!value) return nullptr;
    std::u16string utf16 = utf8ToUtf16(value);
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()),
                          static_cast<jsize>(utf16.size()));
}

std::string jstringToUtf8(JNIEnv *env, jstring value) {
    if (!value) return {};
    jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    std::string result = utf16ToUtf8(chars, length);
    env->ReleaseStringChars(value, chars);
    return result;
}

// ---------------------------------------------------------------------------
// Node conversion
// ---------------------------------------------------------------------------

jobject nodeToJava(JNIEnv *env, const mpv_node &node);

jobjectArray newNodeArray(JNIEnv *env, jsize count) {
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray array = env->NewObjectArray(count, objectClass, nullptr);
    env->DeleteLocalRef(objectClass);
    return array;
}

jobject stringArrayToJava(JNIEnv *env, const char *const *values, int count) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(count, stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    for (int i = 0; i < count; ++i) {
        jstring value = newStringUtf8(env, values[i]);
        env->SetObjectArrayElement(array, i, value);
        if (value) env->DeleteLocalRef(value);
    }
    return array;
}

jobject nodeToJava(JNIEnv *env, const mpv_node &node) {
    switch (node.format) {
        case MPV_FORMAT_NONE:
            return nullptr;
        case MPV_FORMAT_STRING:
            return newStringUtf8(env, node.u.string);
        case MPV_FORMAT_OSD_STRING:
            return newStringUtf8(env, node.u.string);
        case MPV_FORMAT_FLAG: {
            jclass cls = env->FindClass("java/lang/Boolean");
            jmethodID method = env->GetStaticMethodID(cls, "valueOf", "(Z)Ljava/lang/Boolean;");
            jobject result = env->CallStaticObjectMethod(cls, method, node.u.flag != 0);
            env->DeleteLocalRef(cls);
            return result;
        }
        case MPV_FORMAT_INT64: {
            jclass cls = env->FindClass("java/lang/Long");
            jmethodID method = env->GetStaticMethodID(cls, "valueOf", "(J)Ljava/lang/Long;");
            jobject result = env->CallStaticObjectMethod(cls, method, static_cast<jlong>(node.u.int64));
            env->DeleteLocalRef(cls);
            return result;
        }
        case MPV_FORMAT_DOUBLE: {
            jclass cls = env->FindClass("java/lang/Double");
            jmethodID method = env->GetStaticMethodID(cls, "valueOf", "(D)Ljava/lang/Double;");
            jobject result = env->CallStaticObjectMethod(cls, method, static_cast<jdouble>(node.u.double_));
            env->DeleteLocalRef(cls);
            return result;
        }
        case MPV_FORMAT_NODE_ARRAY: {
            const mpv_node_list *list = node.u.list;
            int count = list ? list->num : 0;
            jobjectArray array = newNodeArray(env, count);
            for (int i = 0; i < count; ++i) {
                jobject value = nodeToJava(env, list->values[i]);
                env->SetObjectArrayElement(array, i, value);
                if (value) env->DeleteLocalRef(value);
            }
            return array;
        }
        case MPV_FORMAT_NODE_MAP: {
            const mpv_node_list *list = node.u.list;
            int count = list ? list->num : 0;
            jclass mapClass = env->FindClass("java/util/LinkedHashMap");
            jmethodID constructor = env->GetMethodID(mapClass, "<init>", "(I)V");
            jobject map = env->NewObject(mapClass, constructor, static_cast<jint>(count));
            jmethodID put = env->GetMethodID(
                mapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
            for (int i = 0; i < count; ++i) {
                jstring key = newStringUtf8(env, list->keys[i]);
                jobject value = nodeToJava(env, list->values[i]);
                env->CallObjectMethod(map, put, key, value);
                if (key) env->DeleteLocalRef(key);
                if (value) env->DeleteLocalRef(value);
            }
            env->DeleteLocalRef(mapClass);
            return map;
        }
        case MPV_FORMAT_BYTE_ARRAY: {
            const mpv_byte_array *array = node.u.ba;
            jsize size = array ? static_cast<jsize>(array->size) : 0;
            jbyteArray result = env->NewByteArray(size);
            if (size > 0) {
                env->SetByteArrayRegion(result, 0, size, static_cast<const jbyte *>(array->data));
            }
            return result;
        }
        default:
            return nullptr;
    }
}

// Keeps every allocation referenced by an mpv_node tree alive until the API
// call that consumes it returns. deques keep references stable while nested
// nodes are appended.
struct NodeArena {
    std::deque<std::string> strings;
    std::deque<std::vector<mpv_node>> arrays;
    std::deque<std::vector<char *>> keyArrays;
    std::deque<std::vector<uint8_t>> byteArrays;
    std::deque<mpv_byte_array> byteArrayStructs;
    std::deque<mpv_node_list> lists;
};

bool javaToNode(JNIEnv *env, jobject value, mpv_node &out, NodeArena &arena);

bool javaStringToNode(JNIEnv *env, jstring value, mpv_node &out, NodeArena &arena) {
    arena.strings.push_back(jstringToUtf8(env, value));
    out.format = MPV_FORMAT_STRING;
    out.u.string = const_cast<char *>(arena.strings.back().c_str());
    return true;
}

bool isInstanceOf(JNIEnv *env, jobject value, const char *className) {
    jclass cls = env->FindClass(className);
    if (!cls) return false;
    bool result = env->IsInstanceOf(value, cls);
    env->DeleteLocalRef(cls);
    return result;
}

bool javaToNode(JNIEnv *env, jobject value, mpv_node &out, NodeArena &arena) {
    if (!value) {
        out.format = MPV_FORMAT_NONE;
        return true;
    }

    if (isInstanceOf(env, value, "java/lang/String")) {
        return javaStringToNode(env, static_cast<jstring>(value), out, arena);
    }
    if (isInstanceOf(env, value, "java/lang/Boolean")) {
        jclass cls = env->FindClass("java/lang/Boolean");
        jmethodID method = env->GetMethodID(cls, "booleanValue", "()Z");
        out.format = MPV_FORMAT_FLAG;
        out.u.flag = env->CallBooleanMethod(value, method) ? 1 : 0;
        env->DeleteLocalRef(cls);
        return true;
    }
    if (isInstanceOf(env, value, "java/lang/Long") ||
        isInstanceOf(env, value, "java/lang/Integer") ||
        isInstanceOf(env, value, "java/lang/Short")) {
        jclass cls = env->FindClass("java/lang/Number");
        jmethodID method = env->GetMethodID(cls, "longValue", "()J");
        out.format = MPV_FORMAT_INT64;
        out.u.int64 = env->CallLongMethod(value, method);
        env->DeleteLocalRef(cls);
        return true;
    }
    if (isInstanceOf(env, value, "java/lang/Double") ||
        isInstanceOf(env, value, "java/lang/Float")) {
        jclass cls = env->FindClass("java/lang/Number");
        jmethodID method = env->GetMethodID(cls, "doubleValue", "()D");
        out.format = MPV_FORMAT_DOUBLE;
        out.u.double_ = env->CallDoubleMethod(value, method);
        env->DeleteLocalRef(cls);
        return true;
    }
    jclass byteArrayClass = env->FindClass("[B");
    if (env->IsInstanceOf(value, byteArrayClass)) {
        auto array = static_cast<jbyteArray>(value);
        jsize length = env->GetArrayLength(array);
        arena.byteArrays.emplace_back(static_cast<size_t>(length));
        std::vector<uint8_t> &bytes = arena.byteArrays.back();
        if (length > 0) {
            env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(bytes.data()));
        }
        arena.byteArrayStructs.emplace_back();
        mpv_byte_array &ba = arena.byteArrayStructs.back();
        ba.data = bytes.empty() ? nullptr : bytes.data();
        ba.size = static_cast<size_t>(length);
        out.format = MPV_FORMAT_BYTE_ARRAY;
        out.u.ba = &ba;
        env->DeleteLocalRef(byteArrayClass);
        return true;
    }
    env->DeleteLocalRef(byteArrayClass);

    jclass objectArrayClass = env->FindClass("[Ljava/lang/Object;");
    if (env->IsInstanceOf(value, objectArrayClass)) {
        auto array = static_cast<jobjectArray>(value);
        jsize length = env->GetArrayLength(array);
        arena.arrays.emplace_back(static_cast<size_t>(length));
        std::vector<mpv_node> &nodes = arena.arrays.back();
        for (jsize i = 0; i < length; ++i) {
            jobject element = env->GetObjectArrayElement(array, i);
            javaToNode(env, element, nodes[i], arena);
            if (element) env->DeleteLocalRef(element);
        }
        arena.lists.emplace_back();
        mpv_node_list &list = arena.lists.back();
        list.num = length;
        list.values = nodes.empty() ? nullptr : nodes.data();
        list.keys = nullptr;
        out.format = MPV_FORMAT_NODE_ARRAY;
        out.u.list = &list;
        env->DeleteLocalRef(objectArrayClass);
        return true;
    }
    env->DeleteLocalRef(objectArrayClass);

    jclass mapClass = env->FindClass("java/util/Map");
    if (env->IsInstanceOf(value, mapClass)) {
        jmethodID entrySetMethod = env->GetMethodID(mapClass, "entrySet", "()Ljava/util/Set;");
        jobject entrySet = env->CallObjectMethod(value, entrySetMethod);
        jclass setClass = env->FindClass("java/util/Set");
        jmethodID toArrayMethod = env->GetMethodID(setClass, "toArray", "()[Ljava/lang/Object;");
        auto entries = static_cast<jobjectArray>(env->CallObjectMethod(entrySet, toArrayMethod));
        jsize length = env->GetArrayLength(entries);

        arena.arrays.emplace_back(static_cast<size_t>(length));
        arena.keyArrays.emplace_back(static_cast<size_t>(length), nullptr);
        std::vector<mpv_node> &nodes = arena.arrays.back();
        std::vector<char *> &keys = arena.keyArrays.back();

        jclass entryClass = env->FindClass("java/util/Map$Entry");
        jmethodID getKey = env->GetMethodID(entryClass, "getKey", "()Ljava/lang/Object;");
        jmethodID getValue = env->GetMethodID(entryClass, "getValue", "()Ljava/lang/Object;");
        for (jsize i = 0; i < length; ++i) {
            jobject entry = env->GetObjectArrayElement(entries, i);
            auto key = static_cast<jstring>(env->CallObjectMethod(entry, getKey));
            jobject entryValue = env->CallObjectMethod(entry, getValue);
            keys[i] = const_cast<char *>(arena.strings.emplace_back(jstringToUtf8(env, key)).c_str());
            javaToNode(env, entryValue, nodes[i], arena);
            env->DeleteLocalRef(key);
            if (entryValue) env->DeleteLocalRef(entryValue);
            if (entry) env->DeleteLocalRef(entry);
        }
        env->DeleteLocalRef(entryClass);
        env->DeleteLocalRef(entries);
        env->DeleteLocalRef(setClass);
        env->DeleteLocalRef(entrySet);

        arena.lists.emplace_back();
        mpv_node_list &list = arena.lists.back();
        list.num = length;
        list.values = nodes.empty() ? nullptr : nodes.data();
        list.keys = keys.empty() ? nullptr : keys.data();
        out.format = MPV_FORMAT_NODE_MAP;
        out.u.list = &list;
        env->DeleteLocalRef(mapClass);
        return true;
    }
    env->DeleteLocalRef(mapClass);

    out.format = MPV_FORMAT_NONE;
    return false;
}

jobjectArray errorAndValue(JNIEnv *env, jint error, jobject value) {
    jobjectArray result = newNodeArray(env, 2);
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID valueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject errorObject = env->CallStaticObjectMethod(integerClass, valueOf, error);
    env->SetObjectArrayElement(result, 0, errorObject);
    env->DeleteLocalRef(errorObject);
    env->DeleteLocalRef(integerClass);
    if (value) env->SetObjectArrayElement(result, 1, value);
    return result;
}

mpv_node argsToNode(JNIEnv *env, jobjectArray args, NodeArena &arena) {
    jsize count = args ? env->GetArrayLength(args) : 0;
    arena.arrays.emplace_back(static_cast<size_t>(count));
    std::vector<mpv_node> &nodes = arena.arrays.back();
    for (jsize i = 0; i < count; ++i) {
        auto arg = static_cast<jstring>(env->GetObjectArrayElement(args, i));
        javaStringToNode(env, arg, nodes[i], arena);
        if (arg) env->DeleteLocalRef(arg);
    }
    arena.lists.emplace_back();
    mpv_node_list &list = arena.lists.back();
    list.num = count;
    list.values = nodes.empty() ? nullptr : nodes.data();
    list.keys = nullptr;
    mpv_node result{};
    result.format = MPV_FORMAT_NODE_ARRAY;
    result.u.list = &list;
    return result;
}

jobjectArray propertyPayload(JNIEnv *env, const mpv_event_property *property) {
    jobjectArray payload = newNodeArray(env, 3);
    jclass stringClass = env->FindClass("java/lang/String");
    jstring name = newStringUtf8(env, property ? property->name : nullptr);
    env->SetObjectArrayElement(payload, 0, name);
    if (name) env->DeleteLocalRef(name);
    env->DeleteLocalRef(stringClass);

    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID valueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject format = env->CallStaticObjectMethod(
        integerClass, valueOf, property ? static_cast<jint>(property->format) : 0);
    env->SetObjectArrayElement(payload, 1, format);
    env->DeleteLocalRef(format);
    env->DeleteLocalRef(integerClass);

    if (!property || property->format == MPV_FORMAT_NONE || !property->data) return payload;

    jobject value = nullptr;
    switch (property->format) {
        case MPV_FORMAT_STRING:
        case MPV_FORMAT_OSD_STRING:
            value = newStringUtf8(env, *static_cast<char **>(property->data));
            break;
        case MPV_FORMAT_FLAG: {
            jclass cls = env->FindClass("java/lang/Boolean");
            jmethodID method = env->GetStaticMethodID(cls, "valueOf", "(Z)Ljava/lang/Boolean;");
            value = env->CallStaticObjectMethod(cls, method, *static_cast<int *>(property->data) != 0);
            env->DeleteLocalRef(cls);
            break;
        }
        case MPV_FORMAT_INT64: {
            jclass cls = env->FindClass("java/lang/Long");
            jmethodID method = env->GetStaticMethodID(cls, "valueOf", "(J)Ljava/lang/Long;");
            value = env->CallStaticObjectMethod(
                cls, method, static_cast<jlong>(*static_cast<int64_t *>(property->data)));
            env->DeleteLocalRef(cls);
            break;
        }
        case MPV_FORMAT_DOUBLE: {
            jclass cls = env->FindClass("java/lang/Double");
            jmethodID method = env->GetStaticMethodID(cls, "valueOf", "(D)Ljava/lang/Double;");
            value = env->CallStaticObjectMethod(cls, method, *static_cast<double *>(property->data));
            env->DeleteLocalRef(cls);
            break;
        }
        case MPV_FORMAT_NODE:
        case MPV_FORMAT_NODE_ARRAY:
        case MPV_FORMAT_NODE_MAP:
        case MPV_FORMAT_BYTE_ARRAY:
            value = nodeToJava(env, *static_cast<mpv_node *>(property->data));
            break;
        default:
            break;
    }
    if (value) {
        env->SetObjectArrayElement(payload, 2, value);
        env->DeleteLocalRef(value);
    }
    return payload;
}

jobjectArray eventToJava(JNIEnv *env, const mpv_event *event) {
    jobjectArray result = newNodeArray(env, 4);
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID integerValueOf = env->GetStaticMethodID(
        integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longValueOf = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");

    jobject eventId = env->CallStaticObjectMethod(
        integerClass, integerValueOf, static_cast<jint>(event->event_id));
    jobject error = env->CallStaticObjectMethod(
        integerClass, integerValueOf, static_cast<jint>(event->error));
    jobject userdata = env->CallStaticObjectMethod(
        longClass, longValueOf, static_cast<jlong>(event->reply_userdata));
    env->SetObjectArrayElement(result, 0, eventId);
    env->SetObjectArrayElement(result, 1, error);
    env->SetObjectArrayElement(result, 2, userdata);
    env->DeleteLocalRef(eventId);
    env->DeleteLocalRef(error);
    env->DeleteLocalRef(userdata);

    jobject payload = nullptr;
    switch (event->event_id) {
        case MPV_EVENT_LOG_MESSAGE: {
            auto *message = static_cast<mpv_event_log_message *>(event->data);
            jobjectArray fields = newNodeArray(env, 4);
            jstring prefix = newStringUtf8(env, message ? message->prefix : nullptr);
            jstring level = newStringUtf8(env, message ? message->level : nullptr);
            jstring text = newStringUtf8(env, message ? message->text : nullptr);
            env->SetObjectArrayElement(fields, 0, prefix);
            env->SetObjectArrayElement(fields, 1, level);
            env->SetObjectArrayElement(fields, 2, text);
            jobject logLevel = env->CallStaticObjectMethod(
                integerClass, integerValueOf, message ? static_cast<jint>(message->log_level) : 0);
            env->SetObjectArrayElement(fields, 3, logLevel);
            if (prefix) env->DeleteLocalRef(prefix);
            if (level) env->DeleteLocalRef(level);
            if (text) env->DeleteLocalRef(text);
            env->DeleteLocalRef(logLevel);
            payload = fields;
            break;
        }
        case MPV_EVENT_GET_PROPERTY_REPLY:
        case MPV_EVENT_PROPERTY_CHANGE:
            payload = propertyPayload(env, static_cast<mpv_event_property *>(event->data));
            break;
        case MPV_EVENT_COMMAND_REPLY: {
            auto *command = static_cast<mpv_event_command *>(event->data);
            if (command && command->result.format != MPV_FORMAT_NONE) {
                payload = nodeToJava(env, command->result);
            }
            break;
        }
        case MPV_EVENT_START_FILE: {
            auto *start = static_cast<mpv_event_start_file *>(event->data);
            payload = env->CallStaticObjectMethod(
                longClass, longValueOf, start ? static_cast<jlong>(start->playlist_entry_id) : 0);
            break;
        }
        case MPV_EVENT_END_FILE: {
            auto *end = static_cast<mpv_event_end_file *>(event->data);
            jobjectArray fields = newNodeArray(env, 5);
            jobject reason = env->CallStaticObjectMethod(
                integerClass, integerValueOf, end ? static_cast<jint>(end->reason) : -1);
            jobject endError = env->CallStaticObjectMethod(
                integerClass, integerValueOf, end ? end->error : 0);
            jobject entry = env->CallStaticObjectMethod(
                longClass, longValueOf, end ? static_cast<jlong>(end->playlist_entry_id) : 0);
            jobject insert = env->CallStaticObjectMethod(
                longClass, longValueOf, end ? static_cast<jlong>(end->playlist_insert_id) : 0);
            jobject count = env->CallStaticObjectMethod(
                integerClass, integerValueOf, end ? end->playlist_insert_num_entries : 0);
            env->SetObjectArrayElement(fields, 0, reason);
            env->SetObjectArrayElement(fields, 1, endError);
            env->SetObjectArrayElement(fields, 2, entry);
            env->SetObjectArrayElement(fields, 3, insert);
            env->SetObjectArrayElement(fields, 4, count);
            env->DeleteLocalRef(reason);
            env->DeleteLocalRef(endError);
            env->DeleteLocalRef(entry);
            env->DeleteLocalRef(insert);
            env->DeleteLocalRef(count);
            payload = fields;
            break;
        }
        case MPV_EVENT_CLIENT_MESSAGE: {
            auto *message = static_cast<mpv_event_client_message *>(event->data);
            payload = stringArrayToJava(env, message ? message->args : nullptr,
                                        message ? message->num_args : 0);
            break;
        }
        case MPV_EVENT_HOOK: {
            auto *hook = static_cast<mpv_event_hook *>(event->data);
            jobjectArray fields = newNodeArray(env, 2);
            jstring name = newStringUtf8(env, hook ? hook->name : nullptr);
            jobject id = env->CallStaticObjectMethod(
                longClass, longValueOf, hook ? static_cast<jlong>(hook->id) : 0);
            env->SetObjectArrayElement(fields, 0, name);
            env->SetObjectArrayElement(fields, 1, id);
            if (name) env->DeleteLocalRef(name);
            env->DeleteLocalRef(id);
            payload = fields;
            break;
        }
        default:
            break;
    }
    if (payload) {
        env->SetObjectArrayElement(result, 3, payload);
        env->DeleteLocalRef(payload);
    }
    env->DeleteLocalRef(integerClass);
    env->DeleteLocalRef(longClass);
    return result;
}

void throwUnsatisfiedLink(JNIEnv *env, const std::string &message) {
    jclass cls = env->FindClass("java/lang/UnsatisfiedLinkError");
    if (cls) env->ThrowNew(cls, message.c_str());
}

bool ensureApiLoaded(JNIEnv *env) {
    std::lock_guard<std::mutex> lock(g_apiMutex);
    if (g_api.library) return true;
    if (!g_loadError.empty()) {
        throwUnsatisfiedLink(env, g_loadError);
        return false;
    }

    std::vector<std::string> candidates;
    const char *override = std::getenv("MPV_KMP_LIBRARY");
    if (override && *override) candidates.emplace_back(override);

#if defined(__APPLE__)
    candidates.emplace_back("libmpv.dylib");
    candidates.emplace_back("libmpv.2.dylib");
    candidates.emplace_back("/opt/homebrew/lib/libmpv.dylib");
    candidates.emplace_back("/usr/local/lib/libmpv.dylib");
#elif defined(_WIN32)
    candidates.emplace_back("mpv-2.dll");
    candidates.emplace_back("libmpv-2.dll");
    candidates.emplace_back("mpv.dll");
#elif defined(__ANDROID__)
    candidates.emplace_back("libmpv.so");
#else
    candidates.emplace_back("libmpv.so.2");
    candidates.emplace_back("libmpv.so");
#endif

    std::string errors;
    for (const std::string &candidate : candidates) {
        void *library = openLibrary(candidate.c_str());
        if (!library) {
            errors += candidate + ": " + loaderError() + "\n";
            continue;
        }
        g_api.library = library;
        break;
    }
    if (!g_api.library) {
        g_loadError =
            "Unable to load libmpv. Install it (macOS: brew install mpv, Debian/Ubuntu: "
            "apt install libmpv2, Windows: put mpv-2.dll next to the application or on PATH) "
            "or set MPV_KMP_LIBRARY to its full path.\n" + errors;
        throwUnsatisfiedLink(env, g_loadError);
        return false;
    }

#define MPV_LOAD(name)                                                                  \
    g_api.name = reinterpret_cast<decltype(g_api.name)>(resolveSymbol("mpv_" #name));   \
    if (!g_api.name) {                                                                  \
        g_loadError = "libmpv is missing symbol mpv_" #name;                            \
        g_api.library = nullptr;                                                        \
        throwUnsatisfiedLink(env, g_loadError);                                         \
        return false;                                                                   \
    }

#ifdef _WIN32
    auto resolveSymbol = [](const char *name) -> void * {
        return reinterpret_cast<void *>(GetProcAddress(
            reinterpret_cast<HMODULE>(g_api.library), name));
    };
#else
    auto resolveSymbol = [](const char *name) -> void * {
        return dlsym(g_api.library, name);
    };
#endif

    MPV_LOAD(create)
    MPV_LOAD(initialize)
    MPV_LOAD(terminate_destroy)
    MPV_LOAD(destroy)
    MPV_LOAD(set_option_string)
    MPV_LOAD(command_node)
    MPV_LOAD(command_node_async)
    MPV_LOAD(command_string)
    MPV_LOAD(get_property)
    MPV_LOAD(get_property_string)
    MPV_LOAD(get_property_osd_string)
    MPV_LOAD(set_property)
    MPV_LOAD(set_property_async)
    MPV_LOAD(get_property_async)
    MPV_LOAD(observe_property)
    MPV_LOAD(unobserve_property)
    MPV_LOAD(wait_event)
    MPV_LOAD(request_log_messages)
    MPV_LOAD(client_name)
    MPV_LOAD(error_string)
    MPV_LOAD(client_api_version)
    g_api.free_ = reinterpret_cast<decltype(g_api.free_)>(resolveSymbol("mpv_free"));
    if (!g_api.free_) {
        g_loadError = "libmpv is missing symbol mpv_free";
        g_api.library = nullptr;
        throwUnsatisfiedLink(env, g_loadError);
        return false;
    }
    MPV_LOAD(free_node_contents)
    MPV_LOAD(wakeup)
#undef MPV_LOAD

    g_api.render_context_create = reinterpret_cast<decltype(g_api.render_context_create)>(
        resolveSymbol("mpv_render_context_create"));
    g_api.render_context_free = reinterpret_cast<decltype(g_api.render_context_free)>(
        resolveSymbol("mpv_render_context_free"));
    g_api.render_context_render = reinterpret_cast<decltype(g_api.render_context_render)>(
        resolveSymbol("mpv_render_context_render"));
    g_api.render_context_update = reinterpret_cast<decltype(g_api.render_context_update)>(
        resolveSymbol("mpv_render_context_update"));
    g_api.render_context_report_swap = reinterpret_cast<decltype(g_api.render_context_report_swap)>(
        resolveSymbol("mpv_render_context_report_swap"));

    return true;
}

inline mpv_handle *handleFrom(jlong handle) {
    return reinterpret_cast<mpv_handle *>(static_cast<intptr_t>(handle));
}

/** Owns the mpv render context and the CPU frame buffer it renders into. */
struct SoftwareRenderContext {
    mpv_render_context *context = nullptr;
    int width = 0;
    int height = 0;
    std::string format;
    std::vector<uint8_t> pixels;
};

inline SoftwareRenderContext *renderContextFrom(jlong handle) {
    return reinterpret_cast<SoftwareRenderContext *>(static_cast<intptr_t>(handle));
}

jobject boxedLong(JNIEnv *env, jlong value) {
    jclass cls = env->FindClass("java/lang/Long");
    jmethodID method = env->GetStaticMethodID(cls, "valueOf", "(J)Ljava/lang/Long;");
    jobject result = env->CallStaticObjectMethod(cls, method, value);
    env->DeleteLocalRef(cls);
    return result;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_cn_enaium_mpv_Jni_nativeCreate(JNIEnv *env, jobject) {
    if (!ensureApiLoaded(env)) return 0;
    return reinterpret_cast<jlong>(g_api.create());
}

JNIEXPORT jint JNICALL Java_cn_enaium_mpv_Jni_nativeInitialize(JNIEnv *env, jobject, jlong handle) {
    if (!ensureApiLoaded(env)) return MPV_ERROR_UNINITIALIZED;
    return g_api.initialize(handleFrom(handle));
}

JNIEXPORT void JNICALL Java_cn_enaium_mpv_Jni_nativeTerminateDestroy(
    JNIEnv *env, jobject, jlong handle) {
    if (!ensureApiLoaded(env)) return;
    g_api.terminate_destroy(handleFrom(handle));
}

JNIEXPORT void JNICALL Java_cn_enaium_mpv_Jni_nativeDestroy(
    JNIEnv *env, jobject, jlong handle) {
    if (!ensureApiLoaded(env)) return;
    g_api.destroy(handleFrom(handle));
}

JNIEXPORT jint JNICALL Java_cn_enaium_mpv_Jni_nativeSetOption(
    JNIEnv *env, jobject, jlong handle, jstring name, jstring value) {
    if (!ensureApiLoaded(env)) return MPV_ERROR_UNINITIALIZED;
    std::string cName = jstringToUtf8(env, name);
    std::string cValue = jstringToUtf8(env, value);
    return g_api.set_option_string(handleFrom(handle), cName.c_str(), cValue.c_str());
}

JNIEXPORT jobjectArray JNICALL Java_cn_enaium_mpv_Jni_nativeCommand(
    JNIEnv *env, jobject, jlong handle, jobjectArray args) {
    if (!ensureApiLoaded(env)) return errorAndValue(env, MPV_ERROR_UNINITIALIZED, nullptr);
    NodeArena arena;
    mpv_node argsNode = argsToNode(env, args, arena);
    mpv_node result{};
    int error = g_api.command_node(handleFrom(handle), &argsNode, &result);
    if (error < 0) return errorAndValue(env, error, nullptr);
    jobject value = result.format == MPV_FORMAT_NONE ? nullptr : nodeToJava(env, result);
    g_api.free_node_contents(&result);
    return errorAndValue(env, error, value);
}

JNIEXPORT jint JNICALL Java_cn_enaium_mpv_Jni_nativeCommandString(
    JNIEnv *env, jobject, jlong handle, jstring command) {
    if (!ensureApiLoaded(env)) return MPV_ERROR_UNINITIALIZED;
    std::string value = jstringToUtf8(env, command);
    return g_api.command_string(handleFrom(handle), value.c_str());
}

JNIEXPORT jint JNICALL Java_cn_enaium_mpv_Jni_nativeCommandAsync(
    JNIEnv *env, jobject, jlong handle, jlong replyUserdata, jobjectArray args) {
    if (!ensureApiLoaded(env)) return MPV_ERROR_UNINITIALIZED;
    NodeArena arena;
    mpv_node argsNode = argsToNode(env, args, arena);
    return g_api.command_node_async(
        handleFrom(handle), static_cast<uint64_t>(replyUserdata), &argsNode);
}

JNIEXPORT jobjectArray JNICALL Java_cn_enaium_mpv_Jni_nativeGetProperty(
    JNIEnv *env, jobject, jlong handle, jstring name, jint format) {
    if (!ensureApiLoaded(env)) return errorAndValue(env, MPV_ERROR_UNINITIALIZED, nullptr);
    std::string cName = jstringToUtf8(env, name);
    mpv_handle *ctx = handleFrom(handle);

    if (format == MPV_FORMAT_STRING || format == MPV_FORMAT_OSD_STRING) {
        char *value = format == MPV_FORMAT_OSD_STRING
                          ? g_api.get_property_osd_string(ctx, cName.c_str())
                          : g_api.get_property_string(ctx, cName.c_str());
        if (!value) {
            return errorAndValue(env, MPV_ERROR_PROPERTY_UNAVAILABLE, nullptr);
        }
        jstring string = newStringUtf8(env, value);
        g_api.free_(value);
        return errorAndValue(env, 0, string);
    }

    if (format == MPV_FORMAT_NODE || format == MPV_FORMAT_NODE_ARRAY ||
        format == MPV_FORMAT_NODE_MAP || format == MPV_FORMAT_BYTE_ARRAY) {
        mpv_node node{};
        int error = g_api.get_property(ctx, cName.c_str(), MPV_FORMAT_NODE, &node);
        if (error < 0) return errorAndValue(env, error, nullptr);
        jobject value = nodeToJava(env, node);
        g_api.free_node_contents(&node);
        return errorAndValue(env, error, value);
    }

    int error;
    jobject value = nullptr;
    if (format == MPV_FORMAT_FLAG) {
        int result = 0;
        error = g_api.get_property(ctx, cName.c_str(), MPV_FORMAT_FLAG, &result);
        if (error >= 0) {
            jclass cls = env->FindClass("java/lang/Boolean");
            jmethodID method = env->GetStaticMethodID(cls, "valueOf", "(Z)Ljava/lang/Boolean;");
            value = env->CallStaticObjectMethod(cls, method, result != 0);
            env->DeleteLocalRef(cls);
        }
    } else if (format == MPV_FORMAT_INT64) {
        int64_t result = 0;
        error = g_api.get_property(ctx, cName.c_str(), MPV_FORMAT_INT64, &result);
        if (error >= 0) {
            jclass cls = env->FindClass("java/lang/Long");
            jmethodID method = env->GetStaticMethodID(cls, "valueOf", "(J)Ljava/lang/Long;");
            value = env->CallStaticObjectMethod(cls, method, static_cast<jlong>(result));
            env->DeleteLocalRef(cls);
        }
    } else if (format == MPV_FORMAT_DOUBLE) {
        double result = 0;
        error = g_api.get_property(ctx, cName.c_str(), MPV_FORMAT_DOUBLE, &result);
        if (error >= 0) {
            jclass cls = env->FindClass("java/lang/Double");
            jmethodID method = env->GetStaticMethodID(cls, "valueOf", "(D)Ljava/lang/Double;");
            value = env->CallStaticObjectMethod(cls, method, result);
            env->DeleteLocalRef(cls);
        }
    } else {
        return errorAndValue(env, MPV_ERROR_UNKNOWN_FORMAT, nullptr);
    }
    return errorAndValue(env, error, value);
}

JNIEXPORT jint JNICALL Java_cn_enaium_mpv_Jni_nativeSetProperty(
    JNIEnv *env, jobject, jlong handle, jstring name, jobject value) {
    if (!ensureApiLoaded(env)) return MPV_ERROR_UNINITIALIZED;
    std::string cName = jstringToUtf8(env, name);
    NodeArena arena;
    mpv_node node{};
    javaToNode(env, value, node, arena);
    return g_api.set_property(
        handleFrom(handle), cName.c_str(), MPV_FORMAT_NODE, &node);
}

JNIEXPORT jint JNICALL Java_cn_enaium_mpv_Jni_nativeSetPropertyAsync(
    JNIEnv *env, jobject, jlong handle, jlong replyUserdata, jstring name, jobject value) {
    if (!ensureApiLoaded(env)) return MPV_ERROR_UNINITIALIZED;
    std::string cName = jstringToUtf8(env, name);
    NodeArena arena;
    mpv_node node{};
    javaToNode(env, value, node, arena);
    return g_api.set_property_async(
        handleFrom(handle), static_cast<uint64_t>(replyUserdata), cName.c_str(),
        MPV_FORMAT_NODE, &node);
}

JNIEXPORT jint JNICALL Java_cn_enaium_mpv_Jni_nativeGetPropertyAsync(
    JNIEnv *env, jobject, jlong handle, jlong replyUserdata, jstring name, jint format) {
    if (!ensureApiLoaded(env)) return MPV_ERROR_UNINITIALIZED;
    std::string cName = jstringToUtf8(env, name);
    return g_api.get_property_async(
        handleFrom(handle), static_cast<uint64_t>(replyUserdata), cName.c_str(),
        static_cast<mpv_format>(format));
}

JNIEXPORT jint JNICALL Java_cn_enaium_mpv_Jni_nativeObserveProperty(
    JNIEnv *env, jobject, jlong handle, jlong replyUserdata, jstring name, jint format) {
    if (!ensureApiLoaded(env)) return MPV_ERROR_UNINITIALIZED;
    std::string cName = jstringToUtf8(env, name);
    return g_api.observe_property(
        handleFrom(handle), static_cast<uint64_t>(replyUserdata), cName.c_str(),
        static_cast<mpv_format>(format));
}

JNIEXPORT jint JNICALL Java_cn_enaium_mpv_Jni_nativeUnobserveProperty(
    JNIEnv *env, jobject, jlong handle, jlong replyUserdata) {
    if (!ensureApiLoaded(env)) return MPV_ERROR_UNINITIALIZED;
    return g_api.unobserve_property(handleFrom(handle), static_cast<uint64_t>(replyUserdata));
}

JNIEXPORT jobjectArray JNICALL Java_cn_enaium_mpv_Jni_nativeWaitEvent(
    JNIEnv *env, jobject, jlong handle, jdouble timeout) {
    if (!ensureApiLoaded(env)) return nullptr;
    const mpv_event *event = g_api.wait_event(handleFrom(handle), timeout);
    if (!event || event->event_id == MPV_EVENT_NONE) return nullptr;
    return eventToJava(env, event);
}

JNIEXPORT jint JNICALL Java_cn_enaium_mpv_Jni_nativeRequestLogMessages(
    JNIEnv *env, jobject, jlong handle, jstring level) {
    if (!ensureApiLoaded(env)) return MPV_ERROR_UNINITIALIZED;
    std::string cLevel = jstringToUtf8(env, level);
    return g_api.request_log_messages(handleFrom(handle), cLevel.c_str());
}

JNIEXPORT jstring JNICALL Java_cn_enaium_mpv_Jni_nativeClientName(
    JNIEnv *env, jobject, jlong handle) {
    if (!ensureApiLoaded(env)) return nullptr;
    return newStringUtf8(env, g_api.client_name(handleFrom(handle)));
}

JNIEXPORT jstring JNICALL Java_cn_enaium_mpv_Jni_nativeErrorString(
    JNIEnv *env, jobject, jint code) {
    if (!ensureApiLoaded(env)) return newStringUtf8(env, nullptr);
    return newStringUtf8(env, g_api.error_string(code));
}

JNIEXPORT jlong JNICALL Java_cn_enaium_mpv_Jni_nativeClientApiVersion(JNIEnv *env, jobject) {
    if (!ensureApiLoaded(env)) return 0;
    return static_cast<jlong>(g_api.client_api_version());
}

JNIEXPORT void JNICALL Java_cn_enaium_mpv_Jni_nativeWakeup(
    JNIEnv *env, jobject, jlong handle) {
    if (!ensureApiLoaded(env)) return;
    g_api.wakeup(handleFrom(handle));
}

JNIEXPORT jobjectArray JNICALL Java_cn_enaium_mpv_Jni_nativeCreateRenderContext(
    JNIEnv *env, jobject, jlong handle, jint width, jint height, jstring format) {
    if (!ensureApiLoaded(env)) {
        return errorAndValue(env, MPV_ERROR_UNINITIALIZED, nullptr);
    }
    if (!g_api.render_context_create || !g_api.render_context_free ||
        !g_api.render_context_render || !g_api.render_context_update ||
        !g_api.render_context_report_swap) {
        return errorAndValue(env, MPV_ERROR_UNSUPPORTED, nullptr);
    }
    if (width <= 0 || height <= 0) {
        return errorAndValue(env, MPV_ERROR_INVALID_PARAMETER, nullptr);
    }

    auto *render = new SoftwareRenderContext();
    render->width = width;
    render->height = height;
    render->format = jstringToUtf8(env, format);
    render->pixels.assign(
        static_cast<size_t>(width) * static_cast<size_t>(height) * 4u, 0);

    mpv_render_param params[2]{};
    params[0].type = MPV_RENDER_PARAM_API_TYPE;
    params[0].data = const_cast<char *>(MPV_RENDER_API_TYPE_SW);
    params[1].type = static_cast<mpv_render_param_type>(0);
    params[1].data = nullptr;

    int error = g_api.render_context_create(
        &render->context, handleFrom(handle), params);
    if (error < 0) {
        delete render;
        return errorAndValue(env, error, nullptr);
    }
    return errorAndValue(env, 0, boxedLong(env, reinterpret_cast<jlong>(render)));
}

JNIEXPORT jint JNICALL Java_cn_enaium_mpv_Jni_nativeRenderUpdate(
    JNIEnv *env, jobject, jlong context) {
    if (!ensureApiLoaded(env)) return 0;
    auto *render = renderContextFrom(context);
    if (!render || !render->context || !g_api.render_context_update) return 0;
    return static_cast<jint>(g_api.render_context_update(render->context));
}

JNIEXPORT jint JNICALL Java_cn_enaium_mpv_Jni_nativeRenderFrame(
    JNIEnv *env, jobject, jlong context, jbyteArray output) {
    if (!ensureApiLoaded(env)) return MPV_ERROR_UNINITIALIZED;
    auto *render = renderContextFrom(context);
    if (!render || !render->context || output == nullptr) {
        return MPV_ERROR_INVALID_PARAMETER;
    }
    jsize needed = static_cast<jsize>(render->pixels.size());
    if (env->GetArrayLength(output) < needed) {
        return MPV_ERROR_INVALID_PARAMETER;
    }

    int size[2] = {render->width, render->height};
    size_t stride = static_cast<size_t>(render->width) * 4u;
    int block_for_target_time = 1;
    mpv_render_param params[6]{};
    params[0].type = MPV_RENDER_PARAM_SW_SIZE;
    params[0].data = size;
    params[1].type = MPV_RENDER_PARAM_SW_FORMAT;
    params[1].data = const_cast<char *>(render->format.c_str());
    params[2].type = MPV_RENDER_PARAM_SW_STRIDE;
    params[2].data = &stride;
    params[3].type = MPV_RENDER_PARAM_SW_POINTER;
    params[3].data = render->pixels.data();
    params[4].type = MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME;
    params[4].data = &block_for_target_time;
    params[5].type = static_cast<mpv_render_param_type>(0);
    params[5].data = nullptr;

    int error = g_api.render_context_render(render->context, params);
    if (error < 0) return error;
    env->SetByteArrayRegion(
        output, 0, needed, reinterpret_cast<const jbyte *>(render->pixels.data()));
    return 0;
}

JNIEXPORT void JNICALL Java_cn_enaium_mpv_Jni_nativeRenderReportSwap(
    JNIEnv *env, jobject, jlong context) {
    if (!ensureApiLoaded(env)) return;
    auto *render = renderContextFrom(context);
    if (!render || !render->context || !g_api.render_context_report_swap) return;
    g_api.render_context_report_swap(render->context);
}

JNIEXPORT void JNICALL Java_cn_enaium_mpv_Jni_nativeFreeRenderContext(
    JNIEnv *env, jobject, jlong context) {
    auto *render = renderContextFrom(context);
    if (!render) return;
    if (ensureApiLoaded(env) && render->context && g_api.render_context_free) {
        g_api.render_context_free(render->context);
    }
    delete render;
}

} // extern "C"
