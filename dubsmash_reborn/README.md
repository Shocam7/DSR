# DubSmash Reborn

> **Platform:** Android · **Language:** Flutter (Dart) + Kotlin  
> **Current phase:** P0 · Foundation — Milestone M0 in progress

## What this is

A dubbing/lip-sync app that rebuilds your real room as the cinematic universe of the audio you're dubbing. Real-time on-device ML compositing, AI-personalized server theming, and audio-synced effects.

## Quick start (after Flutter is installed)

```bash
# 1. Install dependencies
flutter pub get

# 2. Generate code (Riverpod, freezed, Pigeon)
dart run build_runner build --delete-conflicting-outputs
dart run pigeon --input packages/dsr_engine/pigeons/engine.dart

# 3. Static analysis (must be zero warnings)
flutter analyze

# 4. Run tests
flutter test

# 5. Start mock server (separate terminal)
dart run tool/mock_server/server.dart

# 6. Run on device (dev flavor)
flutter run --flavor dev --dart-define-from-file=env/dev.json
```

## Repository layout

```
dubsmash_reborn/
├── lib/
│   ├── app/            # bootstrap, router, theme, flavors
│   ├── core/           # http, db, logging, errors, config
│   ├── features/
│   │   ├── auth/  catalog/  record/  edit/  feed/  share/  settings/
│   │   └── <feature>/{data,domain,presentation}/
│   └── engine/         # Dart facade — the ONLY place that calls native
├── packages/
│   └── dsr_engine/     # local Kotlin plugin
│       ├── pigeons/engine.dart  # Pigeon contract (source of truth)
│       └── android/src/…/
│           ├── EnginePlugin.kt, EngineSession.kt, EngineHostApiImpl.kt
│           └── gl/  ml/  scene/  theme/  governor/  record/  audio/  props/
├── tool/mock_server/   # dev & CI mock backend
├── test/               # Dart unit + widget tests
├── integration_test/   # device integration tests
├── assets/models/      # .tflite files (noCompress in Gradle)
├── env/dev.json        # dart-define values for dev flavor
├── PROGRESS.md         # milestone gate tracking
└── DECISIONS.md        # architecture decision log
```

## Milestones

| # | Name | Status |
|---|------|--------|
| **M0** | Bootstrap & guardrails | 🔄 In progress |
| **M1** | Camera → GL → Flutter | ⏳ |
| **M2** | Recording & audio core | ⏳ |
| **M3** | Person segmentation | ⏳ |
| **M4** | Scene seg & theme runtime | ⏳ |
| **M5** | App shell & catalog | ⏳ |
| **M6** | Scan, descriptor & server theming | ⏳ |
| **M7** | Object swaps | ⏳ |
| **M8** | Atmosphere & cue-sync | ⏳ |
| **M9** | ARCore & Studio render | ⏳ |
| **M10** | Hardening & release | ⏳ |

See [PROGRESS.md](PROGRESS.md) for gate checklists and measurements.  
See [DECISIONS.md](DECISIONS.md) for architecture decisions.  
See [docs/](docs/) for the full Technical Blueprint and Engineering Guide.

## Key invariants (never violate)

1. No video frames cross the Dart boundary — pixels stay in native GPU memory
2. Preview never waits on network or ML
3. Faces and raw video never leave the device
4. Theme is frozen at record-start — export = preview
5. Every effect has a Tier L fallback
6. Dart is the shell; Kotlin is the engine
