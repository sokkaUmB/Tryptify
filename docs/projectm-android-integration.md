# projectM Android integration

The player's MilkDrop visualizer is projectM 4.1.6, built from source with the
NDK and linked into the app. It is wired end to end: native build, audio feed,
preset pack, preset browser, and two ways of showing it in the player. This
file says where each part lives. The rules that keep it working are in
`docs/ui-invariants.md` ("The visualizer has two compositions, and only one at
a time") and in AGENTS.md ("projectM is patched, and its presets are scanned").

## projectM itself

- **Source**: the git submodule `third_party/projectm`, pinned to upstream
  4.1.6 and kept clean. It has a nested submodule of its own
  (`vendor/projectm-eval`), so fetch it recursively, or CMake stops with
  "`vendor/projectm-eval` does not contain a CMakeLists.txt file":

  ```
  git submodule update --init --recursive --depth 1
  ```

- **Build**: `app/src/main/cpp/CMakeLists.txt` adds the submodule with
  `add_subdirectory` and builds projectM's `projectM` and `projectM_playlist`
  targets (`libprojectM-4.so`, `libprojectM-4-playlist.so`). It pins
  `CMAKE_PROJECT_VERSION` to 4.1.6, because projectM's generated `version.h`
  reads the top-level project's version and came out empty, and it cancels
  projectM's debug `d` suffix so one `System.loadLibrary` name works in both
  build types.
- **Patches**: `app/src/main/cpp/projectm_patches/` holds fixed copies of
  projectM source files. `projectm_patches.cmake`, included right after
  `add_subdirectory`, swaps each one into its projectM target. The build fails
  if projectM is not 4.1.6, or if a target no longer lists a patched file, so a
  version bump has to re-check every fix by hand. Today there is one fix,
  `TextureSamplerDescriptor.cpp`: a preset sampling a texture the app does not
  ship left a descriptor with no texture, and the next frame read through it
  (a crash on the render thread, on the first frame).

## The bridge

- **Native**: the `monochrome_visualizer` library (`projectm_bridge.cpp`,
  `projectm_jni.cpp`, `audio_ring_buffer.cpp`), linked against projectM,
  projectM's playlist library, EGL and GLESv3. It creates projectM and its
  playlist over the installed preset folder, renders a frame, takes PCM, and
  carries the settings (preset, shuffle, beat sensitivity, brightness, mesh
  quality, FPS, preset duration and rotation, touch).
- **Kotlin**: `visualizer/ProjectMNativeBridge.kt` loads `projectM-4`,
  `projectM-4-playlist` and `monochrome_visualizer` and wraps the JNI calls.
  `visualizer/ProjectMEngineRepository.kt` (a singleton) owns the bridge:
  it refcounts the surfaces attached to it, and only the first one owns the
  native GL objects. It drains the audio bus at the top of each frame, caps
  the frame rate (`shouldDropFrame`, `nanosUntilNextFrame`), and keeps
  Previous honest with `PresetHistory`. When the visualizer changes preset by
  itself is one setting, `PresetRotationMode`.

## Audio

`visualizer/ProjectMAudioTapProcessor.kt` sits in a Media3
`TeeAudioProcessor` in `PlaybackService`'s audio chains (the normal chain and
the hi-res HAL chain), after the mixer, the EQs and the spectrum tap and
before the speed stages. It converts to float only
while something is listening, and hands frames to
`visualizer/ProjectMAudioBus.kt`, which can hold the visualizer's copy back by
a set delay so the picture does not run ahead of Bluetooth output. The
exclusive USB DAC chain leaves the tap out on purpose: its pump runs on the
renderer thread, and the bus can block on its consumer.

## Presets

- **The pack** is committed at `app/src/main/projectm-assets/presets`: 9,795
  `.milk` files, filed as `Category/Subcategory/author - title.milk`. It is not
  a submodule.
- **Packing**: the Gradle task `PackProjectMPresetsTask`
  (`pack<Variant>ProjectMPresets`, in `app/build.gradle.kts`) zips the folder
  into one `assets/projectm/presets.zip`, sorted and with zeroed times so the
  archive is reproducible and preset ids stay stable. The zip is stored
  uncompressed in the APK (`noCompress += "presets.zip"`). Never put presets
  under `src/main/assets/`: everything there is copied into the APK as is,
  next to the zip. A submodule there once added about 29 MB of loose `.milk`
  files that nothing opened.
- **Install**: `visualizer/ProjectMAssetInstaller.kt` extracts the zip on
  first use, in one streaming pass, into the app's files under
  `projectm/<asset version>/presets`, writes `catalog.json` (an id, a name and
  folder tags per preset), and deletes older versions. Bump its
  `assetVersion` when the pack changes. No textures are shipped; the texture
  folder is created empty.
- **Browsing**: `visualizer/ProjectMPresetCatalog.kt` reads the catalog,
  `visualizer/VisualizerPresetIndex.kt` files it by category, subcategory and
  author, and `ui/player/VisualizerPresetSheet.kt` is the browser in the
  player.
- **Presets that crash**: `visualizer/KnownCrashPresets.kt` lists bundled
  presets that still crash projectM with the patches applied. The app never
  loads them. It is generated by `tools/preset_crash_scan/`, which runs every
  preset through the same patched projectM under AddressSanitizer on a
  headless GLES context. The list is empty as of the 2026-10-02 scan. Rerun it
  whenever projectM, the patches or the pack change. A preset that kills one
  vendor's GPU driver cannot be caught that way, so
  `visualizer/PresetCrashGuard.kt` writes the preset about to load to a
  sentinel file and deletes it on a clean release. When the next process
  finds the file, and (on Android 11 and later) the exit record says native
  crash or ANR, that preset is never loaded again on this phone.

## Where it shows

There are two framings of the one engine, and they are never on screen
together (see `docs/ui-invariants.md`):

- **Hero**: `visualizer/ProjectMRendererView.kt`, a `GLSurfaceView`, in place of
  the artwork, hosted in Compose through `AndroidView` by
  `ui/player/VisualizerComponent.kt`.
- **Ambient**: `visualizer/ProjectMOverlayView.kt`, a `TextureView`, as the
  player's background, hosted by `ui/player/AmbientVisualizerLayer.kt`.
  `visualizer/AmbientCompositePass.kt` copies projectM's frame with
  `glBlitFramebuffer` (projectM 4.1.6 always renders to framebuffer 0) and
  composites it over the blurred cover. The alpha comes from the rendered
  pixels, never from the preset: `ambientAlpha` in the shader, with a tested
  Kotlin twin, both in `visualizer/AmbientVisualizer.kt`, changed together.
  When ambient is on it wins, and the player leaves the hero visualizer for
  the cover.

## References

- https://github.com/projectM-visualizer/projectm
- https://github.com/projectM-visualizer/projectm/blob/master/BUILDING.md
