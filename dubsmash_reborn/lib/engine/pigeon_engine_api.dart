// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// PigeonEngineApi — production implementation of [EngineApi] backed by the
// Pigeon-generated [EngineHostApi]. This is the ONLY file that touches
// [EngineHostApi] directly.

import 'package:dsr_engine/dsr_engine.dart' as pigeon;
import 'engine_api.dart';

/// Production adapter: translates [EngineApi] calls into Pigeon calls.
final class PigeonEngineApi implements EngineApi {
  PigeonEngineApi() : _host = pigeon.EngineHostApi();

  final pigeon.EngineHostApi _host;

  @override
  Future<SessionInfo> createSession(SessionConfig config) async {
    final result = await _host.createSession(
      pigeon.SessionConfig(
        sessionId: config.sessionId,
        tier: pigeon.Tier.values[config.tier.index],
        isFrontCamera: config.isFrontCamera,
      ),
    );
    return SessionInfo(
      textureId: result.textureId,
      previewWidth: result.previewWidth,
      previewHeight: result.previewHeight,
      assignedTier: Tier.values[result.assignedTier.index],
    );
  }

  @override
  Future<void> startPreview(int sessionId) => _host.startPreview(sessionId);

  @override
  void stopPreview(int sessionId) => _host.stopPreview(sessionId);

  @override
  Future<void> switchCamera(int sessionId) => _host.switchCamera(sessionId);

  @override
  void startScan(int sessionId) => _host.startScan(sessionId);

  @override
  Future<String> finishScan(int sessionId) => _host.finishScan(sessionId);

  @override
  void applyTheme(
    int sessionId,
    String themeDir,
    ThemeStage stage,
    int crossfadeMs,
  ) =>
      _host.applyTheme(
        sessionId,
        themeDir,
        pigeon.ThemeStage.values[stage.index],
        crossfadeMs,
      );

  @override
  void setTierOverride(int sessionId, Tier? tier) =>
      _host.setTierOverride(sessionId, tier == null ? null : pigeon.Tier.values[tier.index]);

  @override
  void setBackgroundEffect(
    int sessionId, {
    required bool enabled,
    required bool useBlur,
    required int colorArgb,
    required int blurRadius,
  }) =>
      _host.setBackgroundEffect(
        sessionId,
        enabled,
        useBlur,
        colorArgb,
        blurRadius,
      );

  @override
  Future<void> startRecording(int sessionId, RecordingConfig config) =>
      _host.startRecording(
        sessionId,
        pigeon.RecordingConfig(
          outputPath: config.outputPath,
          width: config.width,
          height: config.height,
          videoBitrateBps: config.videoBitrateBps,
          audioOffsetMs: config.audioOffsetMs,
          audioTrackPath: config.audioTrackPath,
        ),
      );

  @override
  Future<RecordingResult> stopRecording(int sessionId) async {
    final result = await _host.stopRecording(sessionId);
    return RecordingResult(
      outputPath: result.outputPath,
      durationMs: result.durationMs,
      themeId: result.themeId,
    );
  }

  @override
  Future<String> exportToGallery(String filePath) =>
      _host.exportToGallery(filePath);

  @override
  void disposeSession(int sessionId) => _host.disposeSession(sessionId);
}
