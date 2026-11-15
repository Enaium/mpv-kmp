# mpv-kmp player example

A complete player UI built with `mpv-kmp`, `sdl-kmp` and `imgui-kmp`.

## Desktop (JVM)

```bash
./gradlew :examples:player:jvmRun
./gradlew :examples:player:jvmRun --args="--file /path/to/movie.mp4"
SDL_VIDEO_DRIVER=dummy ./gradlew :examples:player:jvmRun --args="--no-audio --frames 300"
```

The window uses SDL3 and the Dear ImGui SDL renderer backend. libmpv is put in
`vo=libmpv` mode; `MPVRenderContext` asks libmpv's software renderer for RGB0
frames, which are uploaded to an SDL streaming texture and drawn with
`ImGui.image`. Controls: path/URL + Load, play/pause, stop, mute, volume,
seek bar, time display, fullscreen, and space/arrow shortcuts. With no
`--file`, the bundled `src/commonMain/resources/sample.webm` is extracted and
played.

`--no-audio` selects the null audio output (headless CI);
`--frames N` exits after N rendered frames.

## Desktop (Kotlin/Native)

Targets: `macosArm64`, `macosX64`, `linuxX64`, `linuxArm64`, `mingwX64`.

```bash
./gradlew :examples:player:linkDebugExecutableMacosArm64
SDL_VIDEO_DRIVER=dummy MPV_KMP_FRAMES=120 MPV_KMP_NO_AUDIO=1 \
  MPV_KMP_FILE="$PWD/examples/player/src/commonMain/resources/sample.webm" \
  ./examples/player/build/bin/macosArm64/debugExecutable/player.kexe
```

The same `PlayerApp` uses the native `MPVRenderContext` (software renderer),
SDL3 and Dear ImGui. The Homebrew (`/opt/homebrew/lib`) or Linux multiarch
libmpv prefix is auto-detected and added as search path/rpath; override it with
`MPV_LIBRARY_DIR` when libmpv lives elsewhere. The library must still be
installed for the runtime.

## Android JVM

```bash
./gradlew :examples:player:android:assembleDebug
# optional explicit libmpv/FFmpeg ABI tree:
MPV_ANDROID_LIBS_DIR=/path/to/android-libs ./gradlew :examples:player:android:assembleDebug
```

`MainActivity` extends `SDLActivity`, loads `sdl_jni` and `mpv_jni`, copies the
bundled sample to `cacheDir`, and runs the shared `PlayerApp` on SDL's thread.
The activity is locked to landscape. When `MPV_ANDROID_LIBS_DIR` is absent the
build fetches the pinned mpv-android runtime (GPL-2.0-or-later) into
`build/mpv-android-libs/<abi>/` and packages all four ABIs. The APK contains
the four `libmpv_jni.so` bridges plus `libmpv.so` and its FFmpeg dependencies.

## Android native

```bash
./gradlew :examples:player:android-native:assembleDebug
# optional explicit libmpv/FFmpeg ABI tree:
MPV_ANDROID_LIBS_DIR=/path/to/android-libs ./gradlew :examples:player:android-native:assembleDebug
```

The player module builds `libmain.so` for all four Android ABIs with an
exported `SDL_main`; the app copies it (and Kotlin/Native's `libc++_shared.so`)
into the APK. The activity is locked to landscape. The same auto-fetched
runtime tree supplies `libmpv.so` at link time and in the APK. The activity
copies `sample.webm` to `filesDir` before SDL starts, and the native entry point
opens that path.

Both Android players hide the status and navigation bars, scale the Dear ImGui
UI by the display density, and make the Play button seek to the beginning when
the current file has reached EOF. The JNI bridge and `libmain.so` are linked
with 16 KiB ELF page alignment, and the fetched mpv-android runtime is already
16 KiB-aligned.

## Browser (wasmJs)

```bash
./gradlew :examples:player:copyWebAssets
python3 -m http.server -d examples/player/build/browserApp 8080
```

The page has the same play/pause, stop, seek, volume and mute controls, using
`MPVWeb` (`HTMLMediaElement`) instead of libmpv. `sample.webm` is served next
to the page.

The browser example is a static build only; npm publication is handled by the
`:mpv-kmp` library module.
