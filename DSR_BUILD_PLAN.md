# DubSmash Reborn — Build Plan & Status

> Generated from reading `DubSmash Reborn: Technical Blueprint` and `DubSmash Reborn: Flutter Engineering Guide (Agent Playbook)`.

## What We're Building

A dubbing/lip-sync app (Android-first Flutter) that **rebuilds the user's real room as the cinematic universe** the audio comes from. Users dub songs and dialogues; the app retextures their walls, swaps props, adds atmosphere, and syncs effects to beats — all in real-time on-device with AI-personalized server theming.

---

## Architecture at a Glance

```
ANDROID CLIENT
  CameraX → GL texture → Perception (ML thread) → Scene Descriptor
                            │ masks, boxes, depth
                            ▼
  GL Compositor ← Theme Runtime ← Theme Cache     Upload (async)
      │   (surfaces, props, atmosphere, relight)         ↑
      ▼                                                   │
  Preview + MediaCodec encoder ← Dub Engine (audio)      │
                                                     HTTPS + SSE
BACKEND
  API Gateway → Theme Service → Variant Selector → Planner (LLM)
                     │                                    │
               Asset Resolver ↔ Vector DB (pools)   Generator GPUs
                     │                                    │
                     └→ Moderation → Packager → CDN
```

**Stack (fixed):** Riverpod, go_router, Dio, drift, freezed + json_serializable, Pigeon, Firebase Crashlytics + Remote Config, flutter_secure_storage, permission_handler. minSdk 26, arm64-v8a + armeabi-v7a.

---

## Milestones (ordered, gate-blocked)

| # | Milestone | Status |
|---|-----------|--------|
| M0 | Bootstrap & guardrails | ✅ Scaffolded — awaiting Flutter install + first run |
| M1 | Camera → GL → Flutter | ⏳ Pending |
| M2 | Recording & audio core | ⏳ Pending |
| M3 | Person segmentation & basic compositing | ⏳ Pending |
| M4 | Scene segmentation & theme runtime | ⏳ Pending |
| M5 | App shell & catalog (can run parallel to M3/M4) | ⏳ Pending |
| M6 | Scan, descriptor & server theming | ⏳ Pending |
| M7 | Object swaps (Tier M/H) | ⏳ Pending |
| M8 | Atmosphere, cue-sync & relight | ⏳ Pending |
| M9 | Depth, ARCore & Studio render | ⏳ Pending |
| M10 | Hardening & release | ⏳ Pending |

---

## M0 Checklist

- [ ] Flutter project created (`dubsmash_reborn`) with flavors: `dev`, `staging`, `prod`
- [ ] Strict `analysis_options.yaml` (zero warnings)
- [ ] Full folder layout per §3 of Engineering Guide
- [ ] CI workflow (`.github/workflows/`)
- [ ] `PROGRESS.md` and `DECISIONS.md` scaffolded
- [ ] `dsr_engine` local plugin created with Pigeon contract
- [ ] `EngineApi` interface + `FakeEngineApi` in `lib/engine/`
- [ ] `tool/mock_server` (catalog, track download, `/theme` SSE with canned bundles)
- [ ] "Hello texture" — native GL color into `SurfaceProducer`, shown via Flutter `Texture`
- [ ] **Gate:** CI green; APK installs on L and M; hello texture at 30+ fps; survives rotate, bg/fg, 20 session cycles — no native memory growth

---

## Key Design Invariants (never violate)

1. **No video frames cross the Dart boundary** — pixels stay in native GPU memory
2. **Preview never waits on network or ML** — render loop uses latest available masks/theme
3. **Faces and raw video never leave the device** — only scene descriptor (+ opt-in face-excluded keyframe) may be uploaded
4. **Theme frozen at record start** — export = preview, version-pinned
5. **Every effect has a Tier L fallback** — no feature ships without a degradation path
6. **Dart is the shell; Kotlin is the engine** — C++ only after profiling proves need

---

## Performance Budgets

| Item | Tier L | Tier M/H |
|------|--------|----------|
| Preview fps | ≥ 24 | ≥ 30 |
| GL render time/frame | ≤ 14 ms | ≤ 12 ms |
| Flutter UI frame | ≤ 8 ms | ≤ 8 ms |
| App memory (PSS) recording | ≤ 350 MB | ≤ 600 MB |
| Theme bundle on disk | ≤ 8 MB | ≤ 25 MB |
| Cold start to camera | ≤ 2.5 s | ≤ 1.5 s |

---

## Open Decisions (to resolve with you)

1. First 3 universes — `licensed` or `inspired` mode for each?
2. Keyframe upload — opt-in by default, or descriptor-only until beta proves quality gain?
3. LLM Planner — on-device vs. self-hosted vs. managed (cost vs. latency)?
4. Face tracking / 3D renderer — build vs. buy (Banuba, DeepAR, Camera Kit vs. in-house)?
5. Studio render — subscription tier inclusion or per-clip purchase?
6. Reference device exact models for L / M / H tiers?

---

## Roadmap

| Phase | Weeks | Scope |
|-------|-------|-------|
| P0 · Foundation | 0–6 | Camera → GL → record, dub engine, licensed audio, person seg |
| P1 · Hand-authored worlds | 6–14 | Wall/floor seg, theme runtime, 3 universe packs, tier L/M, closed beta |
| P2 · AI personalization | 14–26 | Descriptor, planner, generative textures, pools, governor, moderation |
| P3 · Full immersion | 26–40 | Object swaps, depth/ARCore, 3D props, cue-sync, Studio render, scale-out |
