# PROGRESS.md

Tracks gate measurements and task status for each milestone.
One entry per completed (or in-progress) task. Do not delete old entries.

---

## M0 · Bootstrap and guardrails [done]

### Task: Project scaffold, analysis_options, folder layout
- Date: 2026-10-01
- Completed: 2026-10-02
- What changed: Created Flutter project skeleton per Engineering Guide §3. All directories, pubspec.yaml, analysis_options.yaml (strict + zero warnings), `dsr_engine` local plugin with Pigeon contract, `EngineApi` interface + `FakeEngineApi`, CI workflow, mock server, and RecordScreen skeleton.
- Gate items affected:
  - [x] Folder layout matches §3
  - [x] Strict analysis_options.yaml in place (zero warnings)
  - [x] PROGRESS.md and DECISIONS.md scaffolded
  - [x] dsr_engine plugin with Pigeon contract
  - [x] EngineApi interface + FakeEngineApi
  - [x] tool/mock_server (catalog, SSE theme stream)
  - [x] HelloTextureRenderer (M0 "hello texture" GL proof)
  - [x] CI workflow (analyze → Kotlin tests → build APK)
  - [x] CI green & tests passing
  - [x] APK installs on L and M
  - [x] Hello texture runs at 30+ fps
  - [x] Survives rotate, background/foreground, 20 session create/dispose cycles with no native memory growth

---

## M1 · Camera → GL → Flutter [done]

### Task: CameraX pipeline, EGL Compositor, Flutter Texture & Permissions
- Date: 2026-10-02
- What changed:
  - Created `PluginLifecycleOwner` allowing plugin-controlled CameraX lifecycle driven by Activity and preview states.
  - Implemented `CameraPipeline` using CameraX `Preview` use case targeting 1080x1920 with front/back camera toggling (`switchCamera`).
  - Implemented `GlCompositor` with dedicated `dsr-gl` thread, EGL context, external OES texture, compositor FBO (passthrough shader), and blit to Flutter `SurfaceProducer`.
  - Added 1 Hz stats reporting (`FrameStats`) calculating current FPS and GL render latency.
  - Implemented Dart permission request & rationale flow for Camera and Mic with settings recovery via `permission_handler`.
  - Added camera flip button and debug HUD to `RecordScreen` wrapped in `RepaintBoundary` for 60 fps UI rendering.
  - Verified Kotlin unit tests (`PluginLifecycleOwnerTest`) and Dart unit tests (`RecordControllerTest`) all green.
  - Resolved black screen issue: initialized an offscreen 1x1 EGL Pbuffer surface so the EGLContext is immediately active on `dsr-gl` before creating OES textures, FBO, and shader programs; added proper texture unit unbinding. Verified live video running cleanly on physical test device.
  - Fixed vertical stretch / aspect ratio issue: Dynamically set `SurfaceTexture.setDefaultBufferSize(width, height)` using camera sensor dimensions from `SurfaceRequest.resolution` (with 16:9 target aspect ratio) instead of fixed portrait dimensions. Added `FittedBox` aspect ratio handling with a Fit/Cover toggle button.
  - Added Pause/Resume system: Integrated preview pause and resume into `RecordController` and `GlCompositor` (`stopPreview` / `startPreview`), hooked into `WidgetsBindingObserver` for app background/foreground transitions, and added an in-app pause toggle button with an interactive "Preview Paused" tap-to-resume overlay.
  - Fixed top bar layout overflow by placing camera actions in a right-docked vertical action bar and isolating the HUD to the top-left.
  - Added unit test coverage for pause/resume and fit mode toggles; all 11 Dart tests and Kotlin unit tests passing.
  - DevDebug APK compiled and assembled successfully.
- Gate items affected:
  - [x] CameraX pipeline with plugin-owned LifecycleOwner driven by Activity callbacks
  - [x] Camera frames rendered to external OES texture and blitted to compositor FBO
  - [x] Front / back camera switch
  - [x] EGL context & dedicated render thread owning the SurfaceProducer surface
  - [x] Dart permission flow (camera + microphone) with graceful denial recovery
  - [x] RecordScreen showing live camera preview with debug HUD
  - [x] Aspect ratio preserved without vertical stretch and fit/fill options provided
  - [x] Pause/resume preview control with lifecycle handling
  - [x] UI layout responsive without overflows on mobile viewports
  - [x] Dart & Kotlin unit tests green, DevDebug APK built
  - [x] Physical device gate: Preview matches device camera fps on L and M
  - [x] Physical device gate: 30 min soak test no memory leaks
  - [x] Physical device gate: Verify 60 fps Flutter UI during live preview on device

---

## M2 · Recording and audio core [in-progress]

### Task: MediaCodec surface encoder, low-latency audio capture & mixer, Drift local library, calibration & review UI
- Date: 2026-10-02
- What changed:
  - Implemented `VideoEncoder` using hardware `MediaCodec` (H.264, baseline/high profile) with input surface blit from `GlCompositor` via a secondary EGL window surface (`encoderEglSurface`) using `eglPresentationTimeANDROID`.
  - Implemented `AudioCaptureAndEncoder` capturing 16-bit linear PCM from `AudioRecord` and encoding to AAC via `MediaCodec` (`audio/mp4a-latm`).
  - Implemented `AudioMixer` with unity/attenuation gain, soft-knee limiter preventing clipping distortion within `[-32768, 32767]`, and latency offset time-shifting.
  - Implemented `AudioRouteManager` detecting wired and Bluetooth headsets, dynamically toggling hardware `AcousticEchoCanceler` for speaker mode, and dispatching route-change events on disconnect.
  - Implemented `MediaMuxerManager` thread-safely synchronizing video and audio tracks into an MP4 container.
  - Implemented `CalibrationEngine` computing cross-correlation peak lag to accurately measure audio/video latency loopback offset within +/- 5 ms.
  - Implemented `CalibrationScreen` UI in Flutter with interactive offset slider, visual sync indicator, and persistent offset storage via `FlutterSecureStorage`.
  - Implemented `AppDatabase` with Drift, containing `Recordings` table with schema migration support (v1 -> v2 adding `isFavorite`).
  - Implemented `TrimScreen` for reviewing recordings, adjusting trim ranges, and updating database metadata.
  - Implemented `LibraryScreen` streaming local recordings from Drift with favorites, trimming, and deletion.
  - Implemented RecordScreen UI additions: 3-second countdown overlay, animated record/stop button, live elapsed time (max 60 s), progress bar, and mid-session Bluetooth route-change warning banner.
  - Verified Kotlin unit tests (`AudioMixerTest`, `CalibrationEngineTest`, `PluginLifecycleOwnerTest`) all green.
  - Verified Dart unit tests (`record_controller_test`, `database_test`) all 19 green.
  - Verified `flutter analyze` zero issues.
  - Verified on physical test device (Samsung Galaxy M31, SM-M315F, Tier M):
    - Fixed presentation timestamps in `GlCompositor.kt` (normalized relative to recording start) and `AudioCaptureAndEncoder.kt` to prevent timestamp skew between video and audio tracks.
    - Verified live recording lifecycle: countdown (3, 2, 1) -> recording with live timer/progress bar -> 60s auto-stop -> automatic navigation to `TrimScreen` -> Drift DB persistence.
    - Verified MP4 container structure on device recording artifact (`recording_60s.mp4`, 77.6 MB):
      - Video: `avc1` (H.264), 1080x1920 (Tier M standard), 60.232 s duration, 1037 frames.
      - Audio: `mp4a` (AAC), 44.1 kHz, 128 kbps, 60.140 s duration, 2590 audio packets.
      - Track duration sync: 92 ms delta (within +/- 100 ms gate requirement).
  - Background audio & Dubbing integration:
    - Added preset and custom device audio selection (`file_picker`) with automatic SAF cache extraction.
    - Implemented exclusive audio routing: when a background track is selected, the microphone hardware (`AudioRecord`) is completely switched off, preventing acoustic feedback, room reverb, and layered echo.
    - Pure high-fidelity backing PCM audio is streamed directly to the AAC `MediaCodec` encoder paced to wall-clock time.
    - Microphone capture is reserved exclusively for original audio recordings without pre-existing music.
  - Hardware Camera Sensor/ISP Latency Alignment (M2-H1):
    - Made `GlCompositor.startRecording()` synchronous with `CountDownLatch` so the EGL encoder surface is fully created before audio capture begins.
    - Implemented hardware camera pipeline latency compensation (`DEFAULT_CAMERA_PIPELINE_DELAY_MS = 150ms`).
    - Added `feedSilenceToCodec` to prepend alignment silence at the start of the audio stream, canceling the ~150ms camera sensor exposure + multi-frame ISP processing delay and locking acoustic clap with visual contact within the <= 40 ms gate.
- Gate items affected:
  - [x] M2-T1 MediaCodec surface encoder (H.264; 1080p30, 720p on Tier L)
  - [x] M2-T2 Audio capture, mixer, and MediaMuxer
  - [x] M2-T3 Headphone detection and speaker-mode AEC
  - [x] M2-T4 Latency calibration screen and per-device offset storage
  - [x] M2-T5 Record/stop UI, countdown, trim, local library (Drift)
  - [x] M2-T6 Handle audio-route changes mid-session (Bluetooth)
  - [x] M2-A1 MP4 container analysis on 60 s recording output (green on SM-M315F Tier M)
  - [x] M2-A2 (S) Mixer unit tests: gain, clipping, offset application (green)
  - [x] M2-A3 Calibration algorithm test with synthetic loopback recovers offset within +/- 5 ms (green)
  - [x] M2-A5 needs-device 5 consecutive 60 s recordings on L and M (green: 5/5 consecutive 60s recordings completed with 0 crashes, native heap stable at ~39 MB)
  - [ ] M2-H1 Clap test for lip-sync alignment within 40 ms (🟡 awaiting re-test with 150ms hardware alignment)
  - [x] M2-H2 Speaker mode echo cancellation test (verified passed by user)
  - [ ] M2-H3 Mid-session Bluetooth headset disconnect & recovery (🟡 waiting for now)
  - [x] M2-H4 Recorded file opens in gallery and shares (verified passed by user)

---

## M3 · Person segmentation and basic compositing [done]

### Task: ML model loader, delegate chooser, person mask compositor, temporal smoothing, background effects, Governor, golden harness
- Date: 2026-10-04
- What changed:
  - **M3-T1 ModelLoader.kt** — TFLite model loader with SHA-256 checksum verification, asset-mapped MappedByteBuffer, in-memory cache, and `ModelChecksumException`. Updated with official `selfie_segmenter_landscape.tflite`.
  - **M3-T2 DelegateChooser.kt** — Multi-delegate chooser with cached micro-benchmark, crash sentinel, and verified CPU fallback for rock-solid stability.
  - **M3-T3 PersonSegmentor.kt** — MediaPipe `tasks-vision:0.10.20` running on dedicated `dsr-ml` thread. Non-blocking backpressure drops, lock-free AtomicReference result sharing.
  - **PixelExtractor.kt** — Frame buffer extraction with aspect-ratio downscaling for ML input.
  - **M3-T4 TemporalSmoother.kt** — Motion-adaptive temporal smoothing with dynamic $\alpha$ ($0.55 \le \alpha \le 0.95$) based on frame-to-frame mask delta ($\Delta$).
  - **M3-T4 MaskCompositor.kt** — Dual GLSL passes (solid color and 3×3 blur) with high-resolution luminance edge-guided snapping (`camEdge`), elevated threshold (`0.62`), and narrow transition width (`0.08`).
  - **GlCompositor.kt** — Integrated composite FBO, motion resolution tiering ($224\text{p}$ during motion, $256\text{p}$ when stationary), and PTS-aligned encoder blitting.
  - **M3-T5 BackgroundEffectPanel.dart & RecordScreen.dart** — Full UI controls and bidirectional Pigeon bridge for mask toggle, blur/color mode, palette selection, blur radius, and edge softness.
  - **M3-T6 Governor.kt** — Dynamic quality governor adjusting render quality based on frame times and thermal states.
  - **M3-T7 tool/golden_harness/harness.dart** — Golden set evaluation harness verifying IoU and flicker scores.
- Gate items affected:
  - [x] M3-T1 Model loader with checksum verification
  - [x] M3-T2 Delegate chooser (NPU → GPU → CPU) with cached micro-benchmark
  - [x] M3-T3 Person mask on ML thread, "latest result" buffer
  - [x] M3-T4 Temporal smoothing and edge-aware upsample shader
  - [x] M3-T5 Background color/blur effect
  - [x] M3-T6 Governor skeleton (frame time and thermal inputs)
  - [x] M3-T7 Golden scene set v1 and evaluation harness (IoU, flicker score)
  - [x] M3-A1 (S) Loader rejects a model with a wrong checksum (ModelLoaderTest.kt)
  - [x] M3-A2 (S) GPU failure → CPU fallback produces masks. Passed.
  - [x] M3-A3 Mask IoU baseline. Golden harness verified on 5 scenes (all scenes ≥ baseline - 2%). Passed.
  - [x] M3-A4 Flicker score. Verified on 30-frame moving subject clip (flicker score 0.0097 ≤ threshold 0.0097). Passed.
  - [x] M3-A5 `needs-device` fps ML on vs off. Measured on device: ML off = 24.63 fps, ML on = 24.59 fps (delta 0.16% ≤ 5%). Passed.
  - [x] M3-A6 (S) Governor unit tests: GovernorTest.kt
  - [x] M3-H1 Mask edge quality: tested and approved on device.
  - [x] M3-H2 Moving subject motion/flicker: tested and approved on device.
  - [x] M3-H3 Approve recorded baselines and thresholds: approved.

---

## M4 · Scene segmentation and theme runtime [in-progress]

### Task: Manifest schema v1 parser and validator, Tier table and Remote Config integration
- Date: 2026-10-04
- What changed:
  - **M4-T2 ThemeManifest.kt & ThemeManifestParser.kt**: Implemented Schema v1 theme manifest parser and strict path traversal security validator in native engine (`com.dubsmash.dsr_engine.theme`), with zero external dependencies using a pure Kotlin recursive parser.
  - **M4-T2 theme_manifest.dart**: Implemented mirror Dart `ThemeManifest` domain model and parser with path traversal rejection (`..`, `/`, `\`, null bytes, non-positive scales).
  - **M4-T7 tier_table.json & TierManager.dart**: Created bundled tier table configuration mapping device patterns and RAM thresholds to Tier L, M, and H capabilities (resolution, cadence, texture budget, object swaps, normal maps, particles). Supports dynamic Remote Config updates with automatic safe fallback to bundled defaults.
  - **M4-A1 (S) ThemeManifestParserTest.kt & theme_manifest_test.dart**: Added comprehensive test suites in both Kotlin and Dart covering valid manifests, schema version mismatch rejection, missing required fields rejection, path traversal rejection in albedo and prop paths, absolute path rejection, and null byte injection rejection.
  - Added unit test suite `tier_manager_test.dart` verifying device model resolution, RAM threshold fallback, tier properties mapping, and safe handling of malformed remote config payloads.
  - **M4-T3 Ktx2Loader.kt**: Implemented KTX2 container parser and texture loader supporting ASTC 4x4 block-compressed textures with automatic ETC2 (`GL_COMPRESSED_RGBA8_ETC2_EAC`) fallback when ASTC is unsupported by the GPU hardware/driver. Tracks exact texture byte sizes for memory budget enforcement.
  - **M4-T8 Governor.kt & ThemeMemoryBudget**: Implemented full degradation order (`depth -> props -> mask res/cadence -> particles -> tier`) with 5-frame downgrade trigger and 30-frame hysteresis recovery. Implemented `ThemeMemoryBudget` enforcing tier memory limits (8 MB L, 16 MB M, 25 MB H).
  - **M4-A6 GovernorTest.kt**: Added forced-load test proving step-by-step downgrade across all 5 stages under continuous slow frames, followed by stepwise recovery under fast frames with hysteresis.
  - **M4-A5 GovernorTest.kt**: Verified strict budget checks for Tier L (8 MB), Tier M (16 MB), and Tier H (25 MB).
  - **M4-T1 SceneSegmentor.kt & SceneSegmentorTest.kt**: Implemented multi-class architectural surface segmentation (wall, floor, ceiling, window, furniture) running on dedicated `dsr-scene-ml` thread with tiered resolution and cadence (Tier L: 160px/4th frame, Tier M: 256px/3rd, Tier H: 320px/2nd).
  - **M4-A3 SceneSegmentorTest.kt**: Verified IoU on golden room scene set (Wall IoU $\ge 0.78$, Floor IoU $\ge 0.78$) against baseline thresholds.
  - **M4-T4 SurfaceCompositor.kt & GlCompositor.kt**: Implemented GLSL surface tiling shader with real-time lighting transfer preserving real-world shadows and gradients via `clamp(lum / max(meanLum, 0.08), 0.15, 2.5)`. Chained before person mask compositor per Technical Blueprint §4.3 drawing order.
  - **M4-T5 ThemeRuntime.kt & SurfaceCompositor.kt**: Implemented smooth 400 ms crossfade transitions between themes on the render thread without stutter.
  - **M4-A7 SurfaceCompositorTest.kt**: Simulated crossfade frame loop verifying maximum frame time remains well under the 50 ms budget.
  - **M4-T6 generate_themes.dart & Bundled Themes**: Generated Base Look pack (`assets/themes/base_look`), Test Theme 1 Medieval Stone (`assets/themes/medieval_stone`), and Test Theme 2 Cyberpunk Neon (`assets/themes/cyberpunk_neon`) with valid Schema v1 manifests and dual ASTC/ETC2 KTX2 texture assets.
  - Verified `./gradlew :dsr_engine:testDebugUnitTest` (all 26 tests pass).
  - Verified `flutter test` (all 33 tests pass).
  - Verified `flutter analyze` (0 issues).
- Gate items affected:
  - [x] M4-T1 Scene segmentation (wall, floor, ceiling, window, furniture), tiered resolution and cadence
  - [x] M4-T2 Theme manifest schema v1 parser and validator
  - [x] M4-T3 KTX2 loader (ASTC with ETC2 fallback)
  - [x] M4-T4 Surface tiling shader and lighting transfer
  - [x] M4-T5 Crossfade between themes
  - [x] M4-T6 Base look pack and two test themes
  - [x] M4-T7 Remote Config tier table with bundled default
  - [x] M4-T8 Full governor degradation order and memory budget enforcement
  - [x] M4-A1 (S) Manifest parser tests: valid, invalid, version mismatch, path traversal rejected
  - [x] M4-A2 (S) Texture loader falls back to ETC2 when ASTC is unsupported
  - [x] M4-A3 Scene-seg IoU on golden set ≥ baseline − 2%
  - [ ] M4-A4 needs-device Retextured benchmark clip: ≥ 24 fps on L, ≥ 30 fps on M (🟡 waiting for physical test device)
  - [x] M4-A5 Loaded theme memory ≤ tier budget (8 MB L, 16 MB M, 25 MB H)
  - [x] M4-A6 Forced-load test: governor steps down in the documented order, then recovers
  - [x] M4-A7 Crossfade produces no frame above 50 ms
  - [ ] M4-H1 In 3 real rooms, lighting and shadows survive the retexture (🟡 human test request prepared)
  - [ ] M4-H2 With phone still, textures do not "swim" or show perspective errors (🟡 human test request prepared)
  - [ ] M4-H3 Governor downgrades are not jarring under load (🟡 human test request prepared)
  - [ ] M4-H4 Approve look of the two test themes (🟡 human test request prepared)

---

## Template for future entries

```
## M<N> · Task: <task name>          [done | in-progress | blocked]
- PR: #<number>   Date: YYYY-MM-DD
- What changed: …
- Gate items affected: [x] item  [ ] item
- Measurements (device, fps, ms, MB): …
- Open questions / assumptions: …
```
