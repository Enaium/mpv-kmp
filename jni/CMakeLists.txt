cmake_minimum_required(VERSION 3.16)

project(mpv-kmp-jni CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The JNI bridge does not link libmpv: it resolves the shared library at
# runtime (and fails with a descriptive UnsatisfiedLinkError if it is absent).
# That keeps the per-platform JNI artifacts independent of libmpv's large
# dependency tree while still binding the pinned 0.42.0 header API.
if(NOT DEFINED MPV_SOURCE_DIR)
    set(MPV_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../mpv")
endif()

add_library(mpv_jni SHARED mpv_jni.cpp)
set_target_properties(mpv_jni PROPERTIES OUTPUT_NAME mpv_jni)
target_include_directories(mpv_jni PRIVATE
    ${MPV_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

if(ANDROID_JNI)
    # The NDK sysroot ships jni.h on the default include path.
elseif(JNI_INCLUDE_DIR)
    target_include_directories(mpv_jni PRIVATE ${JNI_INCLUDE_DIR})
else()
    find_package(JNI REQUIRED)
    target_include_directories(mpv_jni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
if(JNI_INCLUDE_DIR_PLATFORM)
    target_include_directories(mpv_jni PRIVATE ${JNI_INCLUDE_DIR_PLATFORM})
endif()

if(UNIX AND NOT APPLE AND NOT ANDROID)
    target_link_libraries(mpv_jni PRIVATE ${CMAKE_DL_LIBS})
elseif(ANDROID)
    target_link_libraries(mpv_jni PRIVATE dl)
    # Android 15+ devices can use 16 KiB pages; ELF segments must be aligned
    # accordingly or the loader rejects the library. Keep the bridge stripped
    # like the other packaged Android JNI libraries.
    target_link_options(mpv_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
        "-s"
    )
endif()

if(WIN32)
    set_target_properties(mpv_jni PROPERTIES PREFIX "")
endif()
