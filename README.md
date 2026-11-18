# mpv-kmp

Kotlin Multiplatform bindings for [libmpv](https://github.com/mpv-player/mpv), pinned to **mpv 0.42.0** (`b0841e8c4d156b8403dc1876c4cbd9011eb583f3`).

Library version: **`1.0.0-fixed.0.42.0`** (`<kmp-version>-fixed.<libmpv-version>`).

The client API (`mpv/client.h`, API version 2.6) is exposed through one common
Kotlin API on JVM, Android, macOS, Linux, Windows, the four Android NDKs and
`wasmJs`. The native/JVM backends call real libmpv; the browser backend is an
`HTMLMediaElement` implementation of the same contract, with its limits
reported through `MPVClient.capabilities`.

## Supported platforms

| Platform | KMP target | Backend |
|---|---|---|
| JVM (macOS/Linux/Windows) | `jvm` | `libmpv_jni` bridge, libmpv resolved at runtime |
| Android (JVM) | `android` | same bridge in an AAR; four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), 16 KiB page-aligned |
| macOS | `macosArm64`, `macosX64` | cinterop (`mpv/client.h`, `mpv/render.h`) + `-lmpv` |
| Linux | `linuxX64`, `linuxArm64` | cinterop + `-lmpv` |
| Windows | `mingwX64` | cinterop + `-lmpv` |
| Android native | `androidNativeArm64`, `androidNativeArm32`, `androidNativeX64`, `androidNativeX86` | cinterop + `-lmpv`, 16 KiB page-aligned `libmain.so` |
| Web | `wasmJs` | `HTMLVideoElement` backend, no libmpv |

Not provided: Windows arm64 and Linux arm32 (no Kotlin/Native targets), and
iOS/tvOS (outside this binding's scope). The JVM artifacts cover
`linux-x86_64`, `linux-aarch64`, `darwin-x86_64`, `darwin-aarch64` and
`windows-x86_64`.

## libmpv is a runtime dependency

Unlike [sdl-kmp](https://github.com/Enaium/sdl-kmp), this repository does **not**
compile the player into the bindings. libmpv pulls in FFmpeg, libass,
libplacebo, font shaping and platform audio/video APIs; bundling that stack for
nine targets would force a particular codec/licensing/build configuration on
every consumer. Instead:

- headers are always the pinned ones from the `mpv` submodule;
- Kotlin/Native klibs carry `-lmpv` as a linker option, so the final application
  links against the target's libmpv;
- the JVM/Android JNI bridge `dlopen`s libmpv at runtime and fails with a
  descriptive `UnsatisfiedLinkError` naming the search path and the
  `MPV_KMP_LIBRARY` override if it is absent.

Install libmpv:

```bash
# macOS
brew install mpv

# Debian / Ubuntu
sudo apt install libmpv-dev

# Fedora
sudo dnf install mpv-libs-devel

# Windows: put libmpv's mpv-2.dll next to the application or on PATH
# (or set MPV_KMP_LIBRARY to its absolute path)
```

For JVM/Android, `MPV_KMP_LIBRARY=/absolute/path/to/libmpv.so` overrides the
default search. Kotlin/Native builds can point at a custom install with
`-Pmpv.library.dir=/prefix/lib` (or `MPV_LIBRARY_DIR`).

### Building libmpv from the pinned submodule

```bash
git submodule update --init --recursive

# Install mpv's build dependencies first (see DOCS/compile-windows.md and
# meson setup output for the exact list), then:
meson setup build/libmpv mpv \
  --buildtype=release --prefix="$PWD/build/libmpv-install" \
  -Dlibmpv=true -Dcplayer=false
ninja -C build/libmpv install
```

`mpv/ci/build-macos.sh`, `mpv/ci/build-msys2.sh` and
`mpv/ci/build-mingw64.sh` are upstream's own per-platform build scripts.
Android libmpv builds need the NDK and a cross file; place the resulting
`libmpv.so` per ABI and pass the tree to the AAR build:

```bash
MPV_ANDROID_LIBS_DIR=/path/to/libmpv-android ./gradlew :android-jvm:assembleRelease
# /path/to/libmpv-android/arm64-v8a/libmpv.so, armeabi-v7a/, x86/, x86_64/
```

Without `MPV_ANDROID_LIBS_DIR` the AAR still ships the four `libmpv_jni.so`
bridges; the consuming app must provide `libmpv.so` itself (for example in its
own `jniLibs`). The bridge is safe to ship either way because loading is lazy.

The example APKs are self-contained: their Gradle builds use a pinned
[mpv-android](https://github.com/mpv-android/mpv-android) universal release
(`2026-09-17`) when `MPV_ANDROID_LIBS_DIR` is not set. The
`fetchAndroidMpvLibs` task downloads that APK, verifies its SHA-256, and
extracts `libmpv.so` plus the FFmpeg dependency set into
`build/mpv-android-libs/<abi>/`. That runtime is GPL-2.0-or-later and is never
included in the published `mpv-kmp` artifacts. Use
`MPV_ANDROID_LIBS_DIR` / `-Pmpv.android.libs.dir` with a libmpv built from the
pinned 0.42.0 submodule when an exact-version runtime is required.

## Usage

```kotlin
import cn.enaium.mpv.*
import kotlinx.coroutines.flow.collect

fun main() = kotlinx.coroutines.runBlocking {
    MPV.create {
        setOption("vo", "gpu-next")
        setOption("hwdec", "auto-safe")
    }.use { player ->
        player.initialize()
        player.observeProperty(MPVProperty.TIME_POS, 1L, MPVFormat.DOUBLE)

        player.command("loadfile", "video.mp4")

        player.events.collect { event ->
            when (event) {
                is MPVEvent.FileLoaded ->
                    println("duration: ${player.getPropertyDouble(MPVProperty.DURATION)}")

                is MPVEvent.PropertyChange ->
                    println("${event.name} = ${event.value}")

                is MPVEvent.EndFile ->
                    println("ended: ${event.reason}")

                else -> Unit
            }
        }
    }
}
```

The API mirrors libmpv's synchronous client API: options before
`initialize()`, commands, properties in `STRING`/`FLAG`/`INT64`/`DOUBLE`/`NODE`
formats, property observation, synchronous and asynchronous commands, log
messages and the full event set. `MPVNode` is an immutable copy of `mpv_node`
(`Text`, `Flag`, `Int64`, `DoubleValue`, `ArrayValue`, `MapValue`,
`BytesValue`), so no native pointer escapes the binding.

The JVM, Android and Kotlin/Native backends expose libmpv's software renderer
for embedding video in a host UI (the desktop player uploads each frame to an
SDL texture):

```kotlin
val player = MPV.create { setOption("vo", "libmpv") }
player.initialize()
val render = player.createRenderContext(1280, 720)
render.use {
    player.command("loadfile", "video.webm")
    if (render.update()) {
        val frame = render.render() // width, height, stride, RGB0 pixels
        uploadToTexture(frame!!.pixels, frame.stride)
        render.reportSwap()
    }
}
```

`mpv/render.h` and `mpv/render_gl.h` are also bound for Kotlin/Native through
cinterop, so native applications can build OpenGL/Metal-style renderers with
the low-level API. The OpenGL/Metal/Vulkan/D3D11 high-level backends are still
application-owned; this revision provides the CPU software target on all
libmpv backends.

Every client reports what the backend really does:

```kotlin
player.capabilities.platform                 // "jvm" | "android" | "native" | "wasmJs"
player.capabilities.support(MPVFeature.SHADERS) // SUPPORTED | PARTIAL | EMULATED | UNSUPPORTED
```

On desktop/Android every libmpv feature is `SUPPORTED`; in the browser,
`loadfile`/playback/volume/properties are supported or partially supported,
while the render API, shaders, scripting and custom protocols are
`UNSUPPORTED`.

## Web backend

`wasmJs` cannot run libmpv: WebAssembly has no native FFmpeg/audio/video
surface, and browser security policy owns media access. `MPVWeb` implements the
same `MPVClient` interface on `HTMLVideoElement`:

```kotlin
import cn.enaium.mpv.MPVWeb
import kotlinx.browser.document
import org.w3c.dom.HTMLVideoElement
import kotlin.js.unsafeCast

val video = document.getElementById("player")!!.unsafeCast<HTMLVideoElement>()
val player = MPVWeb.create(video)
player.initialize()
player.command("loadfile", "https://example.com/video.mp4")
```

`MPV.create()` also works in the browser and creates a `<video controls>` in
`<body>`. Browser decoding, container and protocol support are the browser's;
mpv-only properties/commands throw `MPVException` with
`MPV_ERROR_PROPERTY_NOT_FOUND`/`MPV_ERROR_UNSUPPORTED`. Property changes are
derived from media events and the `timeupdate` tick, and async replies are
emitted immediately (reported as `EMULATED`).

The wasmJs target also builds a standalone JavaScript/TypeScript module. It is
published as `@enaium/mpv-kmp` and exposes `createPlayer`, `createPlayerFor`,
`initialize`, `command`, `getString`/`getNumber`/`getFlag`, the corresponding
setters, and `close`:

```js
import * as mpv from '@enaium/mpv-kmp'

const video = document.querySelector('video')
const player = mpv.createPlayerFor(video)
mpv.initialize(player)
mpv.command(player, 'loadfile "video.mp4"')
mpv.setFlag(player, 'pause', false)
```

`createPlayer()` creates and appends a `<video controls>` element. The player
handle is an opaque reference; use the exported functions to access it. The
generated TypeScript declarations ship with the package.

## Project layout

```
mpv/                     pinned mpv 0.42.0 source (headers for cinterop)
mpv-kmp/                 the published KMP library
  src/commonMain         API, nodes, events, errors, capabilities
  src/nativeMain         cinterop implementation
  src/jvmMain            JNI implementation + NativeLoader
  src/wasmJsMain         HTMLMediaElement implementation + JS/TypeScript exports
  src/nativeInterop      mpv.def
jni/                     JNI bridge (C++), CMake, Android AAR, per-host modules
examples/mpv_cli         JVM command-line player
examples/player          SDL3 + Dear ImGui player (JVM/native/Android) + wasmJs browser
examples/player/android  Android JVM player APK
examples/player/android-native  Android native player APK (libmain.so)
```

## Build and test

```bash
# host JVM: real libmpv; loads a generated WAV and plays it to EOF
./gradlew :mpv-kmp:jvmTest

# host native (macOS example; Linux/Windows use the matching target)
./gradlew :mpv-kmp:macosArm64Test

# compile bindings for every configured target (no target libmpv required
# for klib compilation)
./gradlew :mpv-kmp:compileKotlinMacosX64 :mpv-kmp:compileKotlinLinuxX64 \
  :mpv-kmp:compileKotlinLinuxArm64 :mpv-kmp:compileKotlinMingwX64 \
  :mpv-kmp:compileKotlinAndroidNativeArm64 \
  :mpv-kmp:compileKotlinAndroidNativeArm32 \
  :mpv-kmp:compileKotlinAndroidNativeX64 \
  :mpv-kmp:compileKotlinAndroidNativeX86

# Android AAR (four ABIs)
./gradlew :android-jvm:assembleRelease

# examples
./gradlew :examples:mpv_cli:run --args="/path/to/video.mp4"

# complete desktop player (SDL3 + Dear ImGui; bundled sample.webm by default)
./gradlew :examples:player:jvmRun
SDL_VIDEO_DRIVER=dummy ./gradlew :examples:player:jvmRun --args="--no-audio --frames 300"

# native desktop player (macOS/Linux/Windows; libmpv must be installed)
./gradlew :examples:player:linkDebugExecutableMacosArm64
SDL_VIDEO_DRIVER=dummy MPV_KMP_FRAMES=120 MPV_KMP_NO_AUDIO=1 \
  MPV_KMP_FILE="$PWD/examples/player/src/commonMain/resources/sample.webm" \
  ./examples/player/build/bin/macosArm64/debugExecutable/player.kexe

# Android JVM APK (fetches the pinned libmpv/FFmpeg ABI tree automatically)
./gradlew :examples:player:android:assembleDebug
# optional explicit runtime tree or prefetch:
MPV_ANDROID_LIBS_DIR=/path/to/android-libs ./gradlew :examples:player:android:assembleDebug
./gradlew fetchAndroidMpvLibs

# Android native APK (same automatic runtime tree)
./gradlew :examples:player:android-native:assembleDebug
MPV_ANDROID_LIBS_DIR=/path/to/android-libs ./gradlew :examples:player:android-native:assembleDebug

# browser player
./gradlew :examples:player:copyWebAssets
python3 -m http.server -d examples/player/build/browserApp 8080

# Kotlin/Wasm npm package for the library (staged in mpv-kmp/build/npm)
./gradlew :mpv-kmp:assembleWasmNpm
```

The desktop player uses `cn.enaium.imgui:imgui-kmp:1.93.0.2` and
`cn.enaium.sdl:sdl-kmp:3.4.18.2` for its SDL/ImGui surface. It provides a media
path/URL input, load, play/pause, stop, mute, volume, a seek bar with position
and duration, fullscreen, and keyboard shortcuts (space, arrows). The same
player class runs on JVM, Kotlin/Native desktop, Android JVM and Android native
(SDLActivity loads `libmain.so` and calls the exported `SDL_main`). The browser
target has the same controls on top of the `MPVWeb` backend. The Android
activities lock landscape, hide system bars, and scale ImGui by display density;
Play at EOF seeks to the start and resumes.

`jvmTest` verifies the full JNI path: player creation, initialization,
properties, observation and playback of a generated WAV to `EndFile(EOF)`.
`macosArm64Test` verifies the cinterop path (creation, properties, property
events). Both need a host libmpv (`brew install mpv` on macOS).

## Publishing

| Artifact | Contents |
|---|---|
| `cn.enaium.mpv:mpv-kmp:1.0.0-fixed.0.42.0` | common API, native klibs, JVM/wasm variants |
| `cn.enaium.mpv:mpv-kmp-jni-jvm-linux-x86_64` | `libmpv_jni.so` |
| `cn.enaium.mpv:mpv-kmp-jni-jvm-linux-aarch64` | `libmpv_jni.so` |
| `cn.enaium.mpv:mpv-kmp-jni-jvm-darwin-x86_64` | `libmpv_jni.dylib` |
| `cn.enaium.mpv:mpv-kmp-jni-jvm-darwin-aarch64` | `libmpv_jni.dylib` |
| `cn.enaium.mpv:mpv-kmp-jni-jvm-windows-x86_64` | `mpv_jni.dll` |
| `cn.enaium.mpv:mpv-kmp-android-jvm` | AAR with four `libmpv_jni.so` ABIs |
| `@enaium/mpv-kmp` (npm) | compiled Kotlin/Wasm browser backend with JS/TypeScript exports |

Each JNI module builds only on its matching host; build every artifact on its
runner before publishing. `.github/workflows/test.yml` publishes the artifacts
to Maven Local, runs the JVM/native tests and the desktop player headless.
`.github/workflows/publish.yml` publishes Maven Central coordinates from their
matching runners. The npm package is published locally with
`:mpv-kmp:publishWasmNpm`.

The wasmJs target also builds a standalone Kotlin/Wasm executable with
`@JsExport` functions (`createPlayer`, `initialize`, `command`,
`getString`/`getNumber`/`getFlag`, and property setters). `:mpv-kmp:assembleWasmNpm`
stages the compiled `.mjs`, `.wasm`, TypeScript declarations, LICENSE, and a
generated npm README in `mpv-kmp/build/npm`; `:mpv-kmp:publishWasmNpm` uploads
it. Examples are not published to npm. The npm version is the project version
verbatim (`1.0.0-fixed.0.42.0`). Provide the token with
`-Pnpm.publish.key=<token>`, `npm.publish.key` in
`~/.gradle/gradle.properties`, or `NODE_AUTH_TOKEN`. Use
`-Pnpm.provenance=true` from CI and `-Pnpm.publish.dryRun=true` to validate the
tarball without uploading.

## Status and limits

- Client API: complete for the 0.42.0 surface used by the Kotlin API
  (commands, properties, observation, async replies, logs, events, nodes).
- Render API: the JVM/Android/Kotlin/Native backends implement libmpv's software renderer
  (`createRenderContext` / `MPVRenderContext`, RGB0/BGR0 frames). The desktop
  player uploads those frames to an SDL texture and draws them through Dear
  ImGui. `mpv/render.h` and `mpv/render_gl.h` are bound for Kotlin/Native
  through cinterop for applications that own an OpenGL/Metal/Vulkan/D3D11
  context; a high-level GPU render context is not provided by this revision.
- Web: no libmpv semantics beyond what `HTMLMediaElement` exposes.
- The JNI bridge is dynamically loaded and is deliberately not linked against
  a specific libmpv build, so one artifact works with any 2.x-compatible
  libmpv. It checks the client API version only through
  `MPV.clientApiVersion()`; the pinned headers guarantee the ABI used.

## License

The project is licensed under **GPL-2.0-or-later** (see `LICENSE`). The `mpv`
submodule retains its own GPL-2.0-or-later/LGPL-2.1-or-later licensing;
libmpv's runtime license depends on how it was built. The Android example
runtime fetched from mpv-android is a GPL-2.0-or-later build and is not part of
the published library artifacts.
