# DECISIONS.md

Architectural decisions made during development.
Never delete entries. Add a supersede note if a decision changes.

Format: `ID · Date · Decision · Alternatives considered · Reason · Who approved`

---

## D001 · 2026-10-01 · Stack locked per Engineering Guide §2

**Decision:** Riverpod (state), go_router (navigation), Dio (HTTP), drift (local DB), freezed + json_serializable (models), Pigeon (native contract), Firebase Crashlytics + Remote Config, flutter_secure_storage, permission_handler.

**Alternatives considered:** BLoC, Provider, GetX (state); auto_route (navigation); Hive/Isar (DB); MethodChannel (native — rejected: lacks type safety).

**Reason:** Specified in the Engineering Guide. Riverpod + code-gen gives compile-time safety and easy test overrides. Pigeon gives typed, versioned native contracts.

**Approved by:** Engineering Guide §2 (pre-approved).

---

## D002 · 2026-10-01 · minSdk 26, arm64-v8a + armeabi-v7a

**Decision:** minSdk 26 (Android 8.0). Build both arm64-v8a and armeabi-v7a ABIs.

**Alternatives considered:** minSdk 24 (wider coverage); arm64 only (simpler, smaller APK).

**Reason:** Specified in the Engineering Guide. armeabi-v7a retained because many cheap phones are still 32-bit. Revisit with human before dropping v7a.

**Approved by:** Engineering Guide §2.

---

## D003 · 2026-10-01 · Flutter 3.47.5 pinned

**Decision:** Pin Flutter 3.47.5 stable (arm64). Verified as latest stable at project start.

**Reason:** Reproducible builds. CI will use the same pinned version.

**Approved by:** Auto-decision at M0.

---

## D004 · 2026-10-01 · Universe strategy: "inspired" mode to start

**Decision:** Launch with `inspired` mode universes only (generic genre looks — dark medieval, sci-fi) — no licensed franchise IP at launch.

**Alternatives considered:** Start with licensed IP (higher ceiling, but blocks launch on rights).

**Reason:** Per Blueprint §13 risk table — rights blocking launch is the top risk. `inspired` mode unblocks P0–P1 entirely.

**Approved by:** User (2026-10-01).

---

## D005 · 2026-10-01 · Keyframe upload: descriptor-only by default

**Decision:** Keyframe upload is opt-in and off by default. Descriptor-only until the quality gain is proven in beta.

**Reason:** Privacy-first design (Blueprint §9). Less legal exposure.

**Approved by:** User (2026-10-01).

---

## D006 · 2026-10-01 · LLM Planner: managed API (Gemini) to start

**Decision:** Use managed Gemini API for the server-side Planner component. Revisit self-hosted vs. on-device after beta validates cost.

**Reason:** Fastest path to P2. Cost validation comes from beta data.

**Approved by:** User (2026-10-01).

---

## D007 · 2026-10-01 · Face tracking: MediaPipe on-device (no buy)

**Decision:** Use MediaPipe Selfie Segmentation + Face Landmarker (both on-device, free). No third-party buy (Banuba, DeepAR) for MVP.

**Reason:** Keeps faces fully on-device (invariant §3). MediaPipe meets the Tier L performance target. Re-evaluate if mask IoU misses gate in M3.

**Approved by:** User (2026-10-01).

---

## D008 · 2026-10-01 · 3D renderer: Filament (in-house) as specified

**Decision:** Use Google Filament for 3D prop rendering (M7+). No buy.

**Reason:** Specified in Blueprint §4.3. Filament is PBR-capable, GPU-accelerated, and has Android support. Integrates cleanly with EGL context.

**Approved by:** User (2026-10-01).

## D009 · 2026-10-02 · M2 Recording and Audio Core Architecture

**Decision:** Use Android `MediaCodec` surface encoder (H.264) connected to `GlCompositor` via a secondary EGL window surface, low-latency audio capture via `AudioRecord` with hardware `AcousticEchoCanceler` for speaker mode, software PCM `AudioMixer` with gain, soft-clipping, and latency offset compensation, and `MediaMuxer` for MP4 export. Local clip library managed via Drift database.

**Alternatives considered:** FFmpeg / libav (adds ~15MB APK overhead and licensing risk); CameraX VideoCapture (incompatible with custom GL compositor FBO pipeline).

**Reason:** Per Engineering Guide §2, §4 and Blueprint §4.7. MediaCodec surface encoder consumes GL frames directly on the render thread with zero memory copies. Drift ensures local database safety and schema migrations.

**Approved by:** Engineering Guide §2 & Blueprint §4.7.

## D010 · 2026-10-02 · Video Preview via video_player and MediaStore Gallery Export

**Decision:** Use official Flutter `video_player` plugin for recorded video preview and scrubbing in `TrimScreen`. Use Android `MediaStore.Video.Media` (API 29+ scoped storage) and `MediaScannerConnection` in native engine to export recorded clips to the public `DCIM/Dubsmash` gallery without requiring broad storage permissions.

**Alternatives considered:** Custom ExoPlayer texture widget (unnecessary duplicate maintenance); third-party gallery plugins with permission bloat.

**Reason:** Official `video_player` integrates cleanly with Flutter rendering. Android MediaStore ContentResolver API is the official Google-recommended pattern for saving media to the user's gallery on Android 10–14 without special runtime permissions.

**Approved by:** User request (2026-10-02).

## D011 · 2026-10-02 · Background Audio Selection via file_picker and Synchronized Playback

**Decision:** Use `file_picker` package to allow users to select custom audio files (MP3, AAC, WAV, M4A, OGG) from device storage for background dubbing/music during recording. Engine plays the audio track in sync during recording and mixes backing audio with microphone capture via `AudioMixer`.

**Alternatives considered:** Custom native intent file picker (less portable); assets-only audio (limits user freedom to dub custom sounds).

**Reason:** User requested ability to play background audio from local files while recording. `file_picker` uses Android's standard Document/Media picker and integrates cleanly without intrusive permissions.

**Approved by:** User request (2026-10-02).

## D012 · 2026-10-03 · Exclusive Audio Routing: Microphone Off During Background Audio Dubbing

**Decision:** Switch off the microphone (`AudioRecord` not instantiated or started) whenever a background audio track is selected. Stream only the clean, high-fidelity backing PCM audio directly into the AAC `MediaCodec` encoder paced by wall-clock time. Use the microphone exclusively when recording original audio without pre-existing music.

**Alternatives considered:** Real-time acoustic echo cancellation (AEC) or software noise suppression to cancel speaker sound from the mic (flawed: introduces phase cancellation, acoustic leakage, and reverberant echo into the recorded video).

**Reason:** When music plays from the device speaker during recording, the microphone picks up the room audio with acoustic delay and reverberation. Layering this mic capture with the pristine digital backing track created an audible double-audio/echo effect. Dubbing videos require the pure studio sound of the chosen audio track.

**Approved by:** User request (2026-10-03).

## D013 · 2026-10-03 · Hardware Camera Sensor/ISP Pipeline Latency Compensation

**Decision:** Apply a calibrated default hardware compensation delay (150 ms) to audio capture before recording, and synchronize `GlCompositor` encoder surface initialization via a `CountDownLatch`. Prepend silence once at the start of the audio stream to align microphone capture with the physical camera sensor exposure and multi-frame ISP processing pipeline.

**Alternatives considered:** Relying on manual calibration screens (rejected: breaks out-of-the-box user experience); modifying timestamps per chunk (flawed: chunk-level offset insertion corrupts audio streaming).

**Reason:** Camera image sensors and ISP pipelines (demosaicing, multi-frame 3D noise reduction, HAL buffer transmission) exhibit ~150 ms physical pipeline delay before frames reach OpenGL. Microphone audio hardware captures sound with only ~15 ms latency. Without hardware delay compensation, acoustic sound is recorded ~135 ms ahead of optical contact, causing visual contact in clap tests to appear visibly late. Prepending 150 ms of alignment silence places audio and video in frame-accurate lockstep (within the M2-H1 40 ms gate requirement).

**Approved by:** User request (2026-10-03).


## D014 · 2026-10-03 · MediaPipe Tasks Vision for On-Device Person Segmentation

**Decision:** Adopt `com.google.mediapipe:tasks-vision:0.10.14` for running the selfie segmentation model instead of stock `org.tensorflow:tensorflow-lite`.

**Alternatives considered:** Stock TensorFlow Lite runtime (rejected: fails to load `selfie_segmentation.tflite` across all delegates due to missing custom op `Convolution2DTransposeBias`); compiling custom C++ TensorFlow Lite kernels (rejected: complex toolchain orchestration, excessive APK bloat, hard to maintain).

**Reason:** `selfie_segmentation.tflite` bundles `SEGMENTER_METADATA` and uses Google's custom `Convolution2DTransposeBias` op. MediaPipe Tasks Vision natively encapsulates this custom op, the TFLite runtime, and high-performance GPU/CPU delegate fallback pipelines specifically optimized for real-time camera processing on mobile hardware without requiring manual kernel linking. Fully conforms to Decision D007 (on-device MediaPipe segmentation without external network calls).

**Approved by:** User consent (2026-10-03).

## D015 · 2026-10-04 · Theme Runtime, KTX2 ASTC/ETC2 Textures and Surface Lighting Transfer
**Decision:** Adopt pure-Kotlin KTX2 container parser with ASTC 4x4 block-compression and automatic ETC2 (`GL_COMPRESSED_RGBA8_ETC2_EAC`) fallback. Implement surface retexturing shader preserving real-world illumination via `clamp(lum(frame) / max(mean_lum(region), 0.08), 0.15, 2.5)` and temporal crossfades (400 ms) in `SurfaceCompositor.kt`. Enforce tier memory budgets (8 MB L, 16 MB M, 25 MB H) and full 5-stage governor degradation cascade (`depth -> props -> mask res/cadence -> particles -> tier`).
**Alternatives considered:** Uncompressed PNG/JPEG textures in theme bundles (rejected: excessive GPU memory footprint, causes OOM on Tier L devices); hardcoded surface tints without lighting transfer (rejected: destroys physical shadows and gradients, leading to an unnatural pasted look).
**Reason:** Conforms to Technical Blueprint §4.1, §4.3, §4.5, and Engineering Guide §4. KTX2 block compression preserves VRAM within tier budgets, and normalized luminance transfer grounds themed surfaces into the physical room's ambient lighting.
**Approved by:** Technical Blueprint §4 & Engineering Guide §4.

---

## Template

```
## D<N> · YYYY-MM-DD · <short title>
**Decision:** …
**Alternatives considered:** …
**Reason:** …
**Approved by:** …
```

