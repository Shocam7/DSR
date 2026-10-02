# PROGRESS.md

Tracks gate measurements and task status for each milestone.
One entry per completed (or in-progress) task. Do not delete old entries.

---

## M0 · Bootstrap and guardrails [in-progress]

### Task: Project scaffold, analysis_options, folder layout
- Date: 2026-10-01
- What changed: Created Flutter project skeleton per Engineering Guide §3. All directories, pubspec.yaml, analysis_options.yaml (strict + zero warnings), `dsr_engine` local plugin with Pigeon contract, `EngineApi` interface + `FakeEngineApi`, CI workflow, mock server, and RecordScreen skeleton.
- Gate items affected:
  - [x] Folder layout matches §3
  - [x] Strict analysis_options.yaml in place
  - [x] PROGRESS.md and DECISIONS.md scaffolded
  - [x] dsr_engine plugin with Pigeon contract
  - [x] EngineApi interface + FakeEngineApi
  - [x] tool/mock_server (catalog, SSE theme stream)
  - [x] HelloTextureRenderer (M0 "hello texture" GL proof)
  - [x] CI workflow (analyze → Kotlin tests → build APK)
  - [ ] CI green (pending Flutter installation + first run)
  - [ ] APK installs on L and M
  - [ ] Hello texture runs at 30+ fps
  - [ ] Survives rotate, background/foreground, 20 session create/dispose cycles with no native memory growth

### Open items before M0 gate passes:
1. Flutter SDK installed — run `flutter pub get` and `dart run build_runner build -d`
2. Run Pigeon: `dart run pigeon --input packages/dsr_engine/pigeons/engine.dart`
3. Run `flutter analyze` — fix any issues, target zero warnings
4. Build dev APK on reference L and M devices
5. Run M0 gate tests (session lifecycle, 20-cycle memory check)

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
