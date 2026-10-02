// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// RecordController — Riverpod notifier that owns the engine session lifecycle
// for the record screen.
//
// Invariants enforced here:
//   - Only one session active at a time.
//   - disposeSession always called on widget dispose (via RecordScreen).
//   - Engine errors downgrade gracefully — they never crash the app.

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../engine/engine_api.dart';
import '../../../engine/engine_providers.dart';

// ─── State ────────────────────────────────────────────────────────────────────

/// Immutable state for the record screen.
final class RecordState {
  const RecordState({
    this.textureId,
    this.previewWidth = 1080,
    this.previewHeight = 1920,
    this.tier = Tier.m,
    this.isRecording = false,
    this.error,
    this.stats,
  });

  final int? textureId;
  final int previewWidth;
  final int previewHeight;
  final Tier tier;
  final bool isRecording;
  final EngineErrorCode? error;
  final FrameStats? stats;

  double get previewAspectRatio => previewWidth / previewHeight;

  RecordState copyWith({
    int? textureId,
    int? previewWidth,
    int? previewHeight,
    Tier? tier,
    bool? isRecording,
    EngineErrorCode? Function()? error,
    FrameStats? Function()? stats,
  }) =>
      RecordState(
        textureId: textureId ?? this.textureId,
        previewWidth: previewWidth ?? this.previewWidth,
        previewHeight: previewHeight ?? this.previewHeight,
        tier: tier ?? this.tier,
        isRecording: isRecording ?? this.isRecording,
        error: error != null ? error() : this.error,
        stats: stats != null ? stats() : this.stats,
      );
}

// ─── Notifier ─────────────────────────────────────────────────────────────────

class RecordController extends Notifier<RecordState> {
  static const int _sessionId = 1;

  EngineApi get _engine => ref.read(engineApiProvider);

  @override
  RecordState build() {
    EngineFlutterApi.setUp(_EngineFlutterApiBridge(this));
    ref.onDispose(() {
      EngineFlutterApi.setUp(null);
    });
    return const RecordState();
  }

  Future<void> startSession() async {
    try {
      final info = await _engine.createSession(
        SessionConfig(
          sessionId: _sessionId,
          tier: Tier.m,
          isFrontCamera: true,
        ),
      );
      state = state.copyWith(
        textureId: info.textureId,
        previewWidth: info.previewWidth,
        previewHeight: info.previewHeight,
        tier: info.assignedTier,
        error: () => null,
      );
      await _engine.startPreview(_sessionId);
    } on Object {
      state = state.copyWith(
        error: () => EngineErrorCode.permission,
      );
    }
  }

  void stopPreview() => _engine.stopPreview(_sessionId);

  Future<void> resumePreview() async {
    if (state.textureId != null) {
      await _engine.startPreview(_sessionId);
    }
  }

  void disposeSession() => _engine.disposeSession(_sessionId);

  /// Called from [EngineFlutterApi.onStats] via the engine event bridge.
  void onStats(FrameStats stats) {
    state = state.copyWith(stats: () => stats, tier: stats.tier);
  }

  /// Called from [EngineFlutterApi.onEvent] via the engine event bridge.
  void onEvent(EngineEvent event) {
    if (event.type == EngineEventType.error && event.errorCode != null) {
      state = state.copyWith(error: () => event.errorCode);
    }
  }
}

// ─── Providers ────────────────────────────────────────────────────────────────

final recordControllerProvider =
    NotifierProvider<RecordController, RecordState>(RecordController.new);

/// Exposes the latest [FrameStats] without rebuilding the whole record screen.
final frameStatsProvider = Provider<FrameStats?>((ref) {
  return ref.watch(recordControllerProvider.select((s) => s.stats));
});

class _EngineFlutterApiBridge implements EngineFlutterApi {
  _EngineFlutterApiBridge(this._controller);
  final RecordController _controller;

  @override
  void onStats(FrameStats stats) {
    _controller.onStats(stats);
  }

  @override
  void onEvent(EngineEvent event) {
    _controller.onEvent(event);
  }
}

