# DubSmash Reborn: Milestone Tasks and Gate Checklists

**Companion to the Flutter Engineering Guide · M0 to M10 · Tracks task IDs, agent tests and human tests separately**

## How to read this document

| Marker | Meaning | Who decides pass/fail |
| --- | --- | --- |
| `[ ] Mx-Tn` | Implementation task | Agent marks done when merged with tests |
| 🤖 `Mx-An` | **Agent test.** Objective, scriptable, repeatable. Has a command or harness and a numeric or boolean threshold | The agent runs it and records the output |
| 👤 `Mx-Hn` | **Human test.** Needs judgment, a physical device in hand, real people, legal or business approval | Only the human. The agent prepares it and waits |

**Gate rule:** a milestone passes only when **every 🤖 test passes and every 👤 test is signed off**. Tasks being done is not enough.

**Status symbols:** ⬜ not run · 🟡 waiting (device or human) · ✅ pass · ❌ fail.

### Rules for the agent

1. **Never mark a 👤 item ✅.** Prepare a *Human Test Request* (template at the end), set the item to 🟡, and move on to unblocked work within the same milestone. Do not start the next milestone.
2. **No device, no pass.** A 🤖 test that needs a physical device (`needs-device`) is 🟡 until it runs on one of the reference devices (L, M, H). Emulator results may be recorded but do not count for fps, memory, thermal or audio tests.
3. **Record evidence** for every item in `PROGRESS.md`: command, output excerpt or file path, device model, build hash.
4. **On ❌:** fix, then re-run **all** 🤖 tests of that milestone plus the standing suite. Do not edit a threshold to make a test pass. Threshold changes go to `DECISIONS.md` and need human approval.
5. **Regression:** the 🤖 tests of earlier milestones that are marked **(S)** join the **standing suite** and run on every PR in CI (or nightly if they need a device).

### Standing suite (every PR)

- 🤖 `S1` `flutter analyze` reports zero issues.
- 🤖 `S2` `flutter test` and `./gradlew :dsr_engine:testDebugUnitTest` pass.
- 🤖 `S3` `flutter build apk --flavor dev` succeeds.
- 🤖 `S4` No new dependency or permission appears in the diff without a linked approval in `DECISIONS.md`.
- 🤖 `S5` Benchmark harness (once M1 exists) shows no regression above 10% in fps or frame time versus the recorded baseline.

---

## M0: Bootstrap and guardrails

**Tasks**

- [x] M0-T1 Create Flutter project with `dev`/`staging`/`prod` flavors and strict lints
- [x] M0-T2 Create folder layout, `PROGRESS.md`, `DECISIONS.md`
- [x] M0-T3 CI workflow (analyze, tests, APK build)
- [x] M0-T4 Create `dsr_engine` plugin with Pigeon codegen
- [x] M0-T5 Define `EngineApi` interface and `FakeEngineApi`
- [x] M0-T6 Build `tool/mock_server` (catalog, track download, SSE theme stages)
- [x] M0-T7 Hello texture: native animated GL into `SurfaceProducer`, shown by `Texture`
- [x] M0-T8 Verify and pin package/API versions against official docs; log in `DECISIONS.md`

**🤖 Agent tests**

- [x] M0-A1 (S) Standing suite S1 to S3 green in CI
- [x] M0-A2 Mock server contract test: SSE emits `base → textures → props` in order, with valid manifest schema v1
- [x] M0-A3 `needs-device` Instrumented test creates and disposes a session 20 times: native heap growth < 5 MB
- [x] M0-A4 `needs-device` Stats event reports hello-texture ≥ 30 fps for 60 s

**👤 Human tests**

- [x] M0-H1 Provide exact models for reference devices L, M, H and approve them
- [x] M0-H2 Install the dev APK on L and M: animated texture is visible, smooth, no tearing or black frames
- [x] M0-H3 Rotate, background and foreground the app: texture recovers each time

---

## M1: Camera → GL → Flutter

**Tasks**

- [x] M1-T1 CameraX with plugin-owned `LifecycleOwner`, front/back switch, orientation handling
- [x] M1-T2 Camera → OES texture → compositor FBO (passthrough) → producer surface; own EGL context and render thread
- [x] M1-T3 Dart permission flow (camera, mic) with all denial paths
- [x] M1-T4 `RecordScreen` preview and debug HUD from `onStats`
- [x] M1-T5 Surface-loss and EGL-context-loss recovery
- [x] M1-T6 Typed `EngineError` mapping to UI messages
- [x] M1-T7 Benchmark harness v1 (frame times to JSON) and baseline recorded

**🤖 Agent tests**

- [x] M1-A1 (S) Permission state machine widget tests cover granted, denied, denied-forever, revoked
- [x] M1-A2 `needs-device` Pause/resume ×50: no crash, session alive, preview resumes
- [x] M1-A3 `needs-device` Preview fps ≥ camera fps − 2 on L and M (harness JSON)
- [x] M1-A4 `needs-device` 30-min soak: native heap growth < 10% (`dumpsys meminfo` script)
- [x] M1-A5 `needs-device` `gfxinfo`: Flutter UI janky frames < 5% with preview running

**👤 Human tests**

- [x] M1-H1 Orientation correct in all 4 rotations; front camera mirroring looks right
- [x] M1-H2 Walk the denial paths by hand (deny, deny forever, revoke in Settings while running): messages are clear and recovery works
- [x] M1-H3 Preview feels live, with no noticeable lag, on L
- [x] M1-H4 Camera switch feels smooth, with no flash or freeze

---

## M2: Recording and audio core

**Tasks**

- [x] M2-T1 `MediaCodec` surface encoder (H.264; 1080p30, 720p on Tier L)
- [x] M2-T2 Oboe playback and mic capture, mixer, `MediaMuxer`
- [x] M2-T3 Headphone detection and speaker-mode echo cancellation
- [x] M2-T4 Latency calibration screen and per-device offset storage
- [x] M2-T5 Record/stop UI, countdown, trim, local library (drift)
- [x] M2-T6 Handle audio-route changes mid-session (Bluetooth)

**🤖 Agent tests**

- [x] M2-A1 `ffprobe` on a 60 s output: codec, resolution/fps per tier, duration within ±100 ms, audio track present
- [x] M2-A2 (S) Mixer unit tests: gain, clipping, offset application
- [x] M2-A3 Calibration algorithm test with synthetic loopback recovers offset within ±5 ms
- [x] M2-A4 drift migration tests pass
- [x] M2-A5 `needs-device` 5 consecutive 60 s recordings on L and M: no crash, dropped encoder frames < 5%

**👤 Human tests**

- [x] M2-H1 Clap test: in frame-by-frame playback the clap sound lands within 40 ms of the visible hand contact, on L and M
- [x] M2-H2 Speaker mode: your voice recording contains no audible echo of the track
- [ ] M2-H3 Connect and disconnect a Bluetooth headset mid-session: warning appears and recording recovers (🟡 waiting for now)
- [x] M2-H4 Recorded file opens in the system gallery and shares to another app

---

## M3: Person segmentation and basic compositing

**Tasks**

- [x] M3-T1 Model loader with checksum verification
- [x] M3-T2 Delegate chooser (NPU → GPU → CPU) with cached micro-benchmark
- [x] M3-T3 Person mask on ML thread, "latest result" buffer
- [x] M3-T4 Temporal smoothing and edge-aware upsample shader
- [x] M3-T5 Background color/blur effect
- [x] M3-T6 Governor skeleton (frame time and thermal inputs)
- [x] M3-T7 Golden scene set v1 and evaluation harness

**🤖 Agent tests**

- [x] M3-A1 (S) Loader rejects a model with a wrong checksum
- [x] M3-A2 (S) Forced GPU-delegate failure falls back to CPU and still produces masks
- [x] M3-A3 Mask IoU on golden set recorded as baseline; later runs must stay ≥ baseline − 2%
- [x] M3-A4 Flicker score ≤ recorded threshold on the moving-subject clip
- [x] M3-A5 `needs-device` Render fps with ML on versus off differs ≤ 5%
- [x] M3-A6 (S) Governor unit tests: synthetic frame times trigger downgrade and recovery with hysteresis

**👤 Human tests**

- [x] M3-H1 Mask edge quality (hair, hands, glasses) on at least 5 people of varied skin tones in 3 lighting conditions: no obvious cut-out look or halo
- [x] M3-H2 Moving subject: flicker is acceptable to the eye
- [x] M3-H3 Approve the recorded baselines and thresholds

---

## M4: Scene segmentation and theme runtime

**Tasks**

- [x] M4-T1 Scene segmentation (wall, floor, ceiling, window, furniture), tiered resolution and cadence
- [x] M4-T2 Theme manifest schema v1 parser and validator
- [x] M4-T3 KTX2 loader (ASTC with ETC2 fallback)
- [x] M4-T4 Surface tiling shader and lighting transfer
- [x] M4-T5 Crossfade between themes
- [x] M4-T6 Base look pack and two test themes
- [x] M4-T7 Remote Config tier table with bundled default
- [x] M4-T8 Full governor degradation order and memory budget enforcement

**🤖 Agent tests**

- [x] M4-A1 (S) Manifest parser tests: valid, invalid, version mismatch, path traversal rejected
- [x] M4-A2 (S) Texture loader falls back to ETC2 when ASTC is unsupported
- [x] M4-A3 Scene-seg IoU on golden set ≥ baseline − 2%
- [ ] M4-A4 `needs-device` Retextured benchmark clip: ≥ 24 fps on L, ≥ 30 fps on M
- [x] M4-A5 Loaded theme memory ≤ tier budget (8 MB L, 16 MB M, 25 MB H)
- [x] M4-A6 Forced-load test: governor steps down in the documented order, then recovers
- [x] M4-A7 Crossfade produces no frame above 50 ms

**👤 Human tests**

- [ ] M4-H1 In 3 real rooms, lighting and shadows survive the retexture (rate 1 to 5; every room ≥ 4)
- [ ] M4-H2 With the phone still, textures do not "swim" or show obvious perspective errors
- [ ] M4-H3 Governor downgrades are not jarring when you load the device
- [ ] M4-H4 Approve the look of the two test themes
---

## M5: App shell and catalog (Dart)

**Tasks**

- [ ] M5-T1 Auth flow
- [ ] M5-T2 Catalog browse/search and track detail
- [ ] M5-T3 Download manager with resume
- [ ] M5-T4 Universe Pack parser (style bible meta, base look, cue track, rights record)
- [ ] M5-T5 Licensing client (territory, expiry, remote kill switch)
- [ ] M5-T6 Feed, share, edit, settings (privacy mode, keyframe consent, quality override)

**🤖 Agent tests**

- [ ] M5-A1 (S) Widget tests for every screen using `FakeEngineApi`
- [ ] M5-A2 (S) Integration test against mock server: login → browse → download → open record screen
- [ ] M5-A3 (S) Out-of-territory, expired and kill-switched tracks are blocked in the UI
- [ ] M5-A4 Offline launch works with cached catalog
- [ ] M5-A5 Download resumes after the app is killed mid-download
- [ ] M5-A6 (S) Pack parser handles missing and extra fields without crashing
- [ ] M5-A7 Golden image tests for key screens; semantics labels present on interactive elements

**👤 Human tests**

- [ ] M5-H1 UX walkthrough on L: from launch to recording in ≤ 3 taps, nothing confusing
- [ ] M5-H2 Licensing and blocking messages approved by product/legal
- [ ] M5-H3 Real sign-in works on a physical device
- [ ] M5-H4 TalkBack pass on core flows

---

## M6: Scan, descriptor and server theming

**Tasks**

- [ ] M6-T1 Guided scan UX
- [ ] M6-T2 Native descriptor builder and clean-plate capture
- [ ] M6-T3 Descriptor upload (Dio) with seed and track ID
- [ ] M6-T4 SSE client for staged results
- [ ] M6-T5 Bundle download with checksum and signature verification, LRU cache
- [ ] M6-T6 Apply and crossfade staged themes
- [ ] M6-T7 Fallbacks: timeout, offline, server error
- [ ] M6-T8 Privacy mode and opt-in face-excluded keyframe

**🤖 Agent tests**

- [ ] M6-A1 (S) Descriptor validates against schema v1 and is < 50 KB
- [ ] M6-A2 Keyframe contains no detectable face (run an on-device face detector over 20 golden frames that include people)
- [ ] M6-A3 (S) Privacy mode: mock server log shows no keyframe in the request
- [ ] M6-A4 (S) Bad checksum or signature: bundle rejected, base look kept
- [ ] M6-A5 (S) Fault injection (timeout, offline, 5xx): base look retained, no crash
- [ ] M6-A6 (S) Same seed and theme version yields an identical bundle hash and render
- [ ] M6-A7 `needs-device` Latency profile: first themed look ≤ 1 s, p50 personalized ≤ 8 s
- [ ] M6-A8 `needs-device` No frame above 50 ms during download and apply

**👤 Human tests**

- [ ] M6-H1 Five first-time users complete the scan without help (≥ 4 of 5)
- [ ] M6-H2 Real server on real networks (4G, weak Wi-Fi): experience feels acceptable
- [ ] M6-H3 Privacy review: consent copy and data-flow diagram approved, matching what the app actually sends
- [ ] M6-H4 In a dev build, review 10 real keyframes: no faces or other people visible

---

## M7: Object swaps (Tier M/H)

**Tasks**

- [ ] M7-T1 Object detection model and optical-flow tracker
- [ ] M7-T2 Class-to-asset mapping from the theme manifest
- [ ] M7-T3 Clean-plate fill of original object pixels
- [ ] M7-T4 Filament offscreen render and composite with depth ordering
- [ ] M7-T5 Per-tier prop cap and async asset loading
- [ ] M7-T6 Disable on Tier L

**🤖 Agent tests**

- [ ] M7-A1 (S) Tier L config disables props; prop cap enforced on M and H
- [ ] M7-A2 Tracking: rendered box IoU with detection ≥ 0.7 over the benchmark clip
- [ ] M7-A3 `needs-device` fps ≥ 30 on M with 3 props
- [ ] M7-A4 (S) Asset loading never runs on the render thread (thread assertion test)
- [ ] M7-A5 (S) `.glb` validator: ≤ 20k triangles, one material, 512² textures, else rejected
- [ ] M7-A6 Memory stays within tier budget with max props

**👤 Human tests**

- [ ] M7-H1 Swap stays locked to the object, with no halo or ghost of the original
- [ ] M7-H2 Scale and position look plausible for bottle, cup, lamp, book, plant
- [ ] M7-H3 Hand or person passing in front of a swapped prop looks acceptable
- [ ] M7-H4 Art direction approval for the prop style

---

## M8: Atmosphere, cue-sync and relight

**Tasks**

- [ ] M8-T1 Color-grade LUT, fog, GPU particles
- [ ] M8-T2 Window/sky replacement
- [ ] M8-T3 Cue track parser and scheduler
- [ ] M8-T4 Person relight (tint, rim light)
- [ ] M8-T5 Governor hooks for every effect

**🤖 Agent tests**

- [ ] M8-A1 (S) Scheduler fires events within ±1 frame at 30 fps on a synthetic clock
- [ ] M8-A2 (S) LUT parser tests; particle counts capped per tier
- [ ] M8-A3 Each effect disables or degrades when the governor demands it
- [ ] M8-A4 `needs-device` fps budgets hold on L and M with full atmosphere
- [ ] M8-A5 Strobe check: automated analysis of rendered clip finds no flash above 3 per second

**👤 Human tests**

- [ ] M8-H1 On a test track, effects visibly and audibly land on the beat
- [ ] M8-H2 Relight looks natural across skin tones
- [ ] M8-H3 Five testers rate overall immersion (average ≥ 4 of 5)
- [ ] M8-H4 Photosensitivity safety review of beat effects

---

## M9: Depth, ARCore (optional) and Studio render

**Tasks**

- [ ] M9-T1 ARCore capability check
- [ ] M9-T2 Plane and depth integration with segmentation-only fallback
- [ ] M9-T3 Studio render upload behind a feature flag (off by default)

**🤖 Agent tests**

- [ ] M9-A1 Device without ARCore: output matches M8 on the benchmark clip (image diff within tolerance)
- [ ] M9-A2 (S) Flag off: no Studio-render requests are made
- [ ] M9-A3 (S) Studio payload inspection on mock server: no raw video, no face data
- [ ] M9-A4 `needs-device` fps budget holds on H with depth enabled

**👤 Human tests**

- [ ] M9-H1 On an ARCore device, wall perspective is visibly better than the fallback
- [ ] M9-H2 Studio render output quality and cost approved
- [ ] M9-H3 Privacy approval of the Studio render data flow

---

## M10: Hardening and release

**Tasks**

- [ ] M10-T1 Soak, thermal and low-memory handling
- [ ] M10-T2 Crashlytics with engine breadcrumbs
- [ ] M10-T3 Data Safety form draft, privacy policy, age gate, consent copy
- [ ] M10-T4 16 KB page-size support for all native libraries; AAB size and model delivery plan
- [ ] M10-T5 Staged rollout plan and per-universe/per-effect kill switches
- [ ] M10-T6 Security hardening (pinning, signed bundles, secret scan)

**🤖 Agent tests**

- [ ] M10-A1 `needs-device` 60-min soak on L, M, H: no crash, native memory growth < 10%, fps p5 within 10% of budget
- [ ] M10-A2 `needs-device` Kill app via `adb` under memory pressure: relaunch restores state and session
- [ ] M10-A3 Native libraries pass the 16 KB alignment check script
- [ ] M10-A4 Release AAB builds; `bundletool` size within agreed target
- [ ] M10-A5 Secret scan and dependency vulnerability scan report nothing high or critical
- [ ] M10-A6 `needs-device` Toggling a kill switch in Remote Config disables the effect within 1 min
- [ ] M10-A7 Full regression: every (S) test from M0 to M9 green

**👤 Human tests**

- [ ] M10-H1 Closed-beta crash-free rate ≥ 99.5% over the agreed sample, read from Crashlytics
- [ ] M10-H2 Data Safety form and privacy policy reviewed against actual data flows by the human/legal
- [ ] M10-H3 Rights and licensing sign-off for each launch track and universe
- [ ] M10-H4 10-min recording on L and M: device not uncomfortably hot, fps holds
- [ ] M10-H5 Final security and privacy checklist signed off
- [ ] M10-H6 Play Console submission and staged rollout go/no-go decision

---

## Templates

**Human Test Request** (agent writes this; human answers)

```
ID: M4-H1      Milestone: M4      Build: <git hash / APK path>
Device(s): L, M
What to do: 1) Open the dev app 2) Choose "Test Theme A" 3) Point at a wall in 3 different rooms …
Expected result: shadows and gradients on the wall remain visible; rating ≥ 4 of 5 per room
What to report: pass/fail, rating per room, screen recording if fail
```

**Gate status table** (keep at the top of each milestone in `PROGRESS.md`)

```
| Item   | Type | Status | Evidence / link              | Date |
| M4-A4  | 🤖   | ✅     | harness JSON, Redmi 9A, 26fps | …    |
| M4-H1  | 👤   | 🟡     | request sent, awaiting human  | …    |
```
