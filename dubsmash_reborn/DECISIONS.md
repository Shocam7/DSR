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

---

## Template

```
## D<N> · YYYY-MM-DD · <short title>
**Decision:** …
**Alternatives considered:** …
**Reason:** …
**Approved by:** …
```
