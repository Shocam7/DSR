// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// FakeEngineApi — in-memory implementation of [EngineApi] for Dart unit and
// widget tests. No native code, no GL, no camera. All methods return
// immediately with plausible values.
//
// Usage in tests:
//   final fake = FakeEngineApi();
//   // Optionally prime behavior:
//   fake.nextDescriptor = '{"schema":1,"track_id":"trk_0","seed":0,"tier":"L",...}';
//   // Override provider:
//   overrides: [engineApiProvider.overrideWithValue(fake)]

import 'dart:async';

import 'engine_api.dart';

/// In-memory fake. Override public fields to control behavior in tests.
final class FakeEngineApi implements EngineApi {
  // ── Controllable outcomes ─────────────────────────────────────────────────

  /// Descriptor JSON returned by [finishScan]. Override for scan-path tests.
  String nextDescriptor = '{'
      '"schema":1,'
      '"track_id":"trk_fake",'
      '"seed":0,'
      '"tier":"L",'
      '"scene":{'
      '"surfaces":{"wall":0.5,"floor":0.3,"ceiling":0.1,"window":0.0},'
      '"objects":[],'
      '"palette":["#aaaaaa","#555555"],'
      '"lighting":{"dir":[0.0,-1.0,0.0],"warmth":0.5,"intensity":0.5},'
      '"layout":{"room_size":"medium","plane_normals":"estimated"}'
      '},'
      '"keyframe_consent":false'
      '}';

  /// Simulated preview dimensions returned by [createSession].
  int previewWidth = 1080;
  int previewHeight = 1920;

  /// Simulated assigned tier.
  Tier assignedTier = Tier.m;

  // ── Call log (assert in tests) ────────────────────────────────────────────

  final List<String> callLog = [];

  // ── State ─────────────────────────────────────────────────────────────────

  int _nextTextureId = 1;
  final Map<int, bool> _activeSessions = {};

  // ── EngineApi implementation ───────────────────────────────────────────────

  @override
  Future<SessionInfo> createSession(SessionConfig config) async {
    callLog.add('createSession(${config.sessionId})');
    final id = _nextTextureId++;
    _activeSessions[config.sessionId] = true;
    return SessionInfo(
      textureId: id,
      previewWidth: previewWidth,
      previewHeight: previewHeight,
      assignedTier: assignedTier,
    );
  }

  @override
  Future<void> startPreview(int sessionId) async {
    callLog.add('startPreview($sessionId)');
  }

  @override
  void stopPreview(int sessionId) {
    callLog.add('stopPreview($sessionId)');
  }

  @override
  void startScan(int sessionId) {
    callLog.add('startScan($sessionId)');
  }

  @override
  Future<String> finishScan(int sessionId) async {
    callLog.add('finishScan($sessionId)');
    return nextDescriptor;
  }

  @override
  void applyTheme(
    int sessionId,
    String themeDir,
    ThemeStage stage,
    int crossfadeMs,
  ) {
    callLog.add('applyTheme($sessionId, $themeDir, $stage, ${crossfadeMs}ms)');
  }

  @override
  void setTierOverride(int sessionId, Tier? tier) {
    callLog.add('setTierOverride($sessionId, $tier)');
  }

  @override
  Future<void> startRecording(int sessionId, RecordingConfig config) async {
    callLog.add('startRecording($sessionId, ${config.outputPath})');
  }

  @override
  Future<RecordingResult> stopRecording(int sessionId) async {
    callLog.add('stopRecording($sessionId)');
    return RecordingResult(
      outputPath: '/fake/output/clip_$sessionId.mp4',
      durationMs: 10000,
      themeId: 'th_fake',
    );
  }

  @override
  void disposeSession(int sessionId) {
    callLog.add('disposeSession($sessionId)');
    _activeSessions.remove(sessionId);
  }
}
