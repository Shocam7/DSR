// dart run pigeon --input packages/dsr_engine/pigeons/engine.dart
//
// This file defines the typed Dart ↔ Kotlin contract.
// After any change here, regenerate with the command above and do a FULL restart
// (hot reload does NOT pick up native changes).
//
// Rules:
//  - Payloads must be small and typed (no raw Maps, no byte arrays for frames).
//  - Errors must use EngineErrorCode enum values, not raw exception strings.
//  - Every method that touches the GL or ML thread must be @async.
//  - disposeSession must release camera, GL, codecs, and models within 500 ms.

import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'packages/dsr_engine/lib/src/messages.g.dart',
    kotlinOut:
        'packages/dsr_engine/android/src/main/kotlin/com/dubsmash/dsr_engine/Messages.g.kt',
    kotlinOptions: KotlinOptions(
      package: 'com.dubsmash.dsr_engine',
    ),
    copyrightHeader: 'packages/dsr_engine/pigeons/copyright_header.txt',
  ),
)

// ─── Enums ───────────────────────────────────────────────────────────────────

/// Device performance tier, derived from remote config lookup + first-launch benchmark.
enum Tier { l, m, h }

/// Stage of the theme being applied. Dart downloads and passes the theme dir;
/// native applies it in stages so the preview updates progressively.
enum ThemeStage { base, textures, props }

/// Typed error codes. Native must never crash the app; it disables the effect
/// and sends one of these codes via [EngineFlutterApi.onEvent].
enum EngineErrorCode {
  permission,
  cameraBusy,
  surfaceLost,
  modelLoadFailed,
  delegateFailed,
  encoderFailed,
  themeInvalid,
  oom,
}

/// Events emitted from the engine to Dart (via [EngineFlutterApi.onEvent]).
enum EngineEventType {
  scanProgress,
  scanComplete,
  tierChanged,
  themeStageReady,
  error,
  recordingStarted,
  recordingStopped,
}

// ─── Data classes ─────────────────────────────────────────────────────────────

/// Configuration passed when creating a new engine session.
class SessionConfig {
  const SessionConfig({
    required this.sessionId,
    required this.tier,
    required this.isFrontCamera,
  });

  final int sessionId;
  final Tier tier;
  final bool isFrontCamera;
}

/// Returned by [EngineHostApi.createSession].
class SessionInfo {
  const SessionInfo({
    required this.textureId,
    required this.previewWidth,
    required this.previewHeight,
    required this.assignedTier,
  });

  /// ID passed to [Texture] widget in Dart.
  final int textureId;
  final int previewWidth;
  final int previewHeight;

  /// The tier actually assigned (may differ from requested if benchmark failed).
  final Tier assignedTier;
}

/// Configuration for starting a recording pass.
class RecordingConfig {
  const RecordingConfig({
    required this.outputPath,
    required this.width,
    required this.height,
    required this.videoBitrateBps,
    required this.audioOffsetMs,
  });

  final String outputPath;
  final int width;
  final int height;
  final int videoBitrateBps;

  /// Device-specific A/V offset measured by the latency calibration screen.
  final int audioOffsetMs;
}

/// Returned by [EngineHostApi.stopRecording].
class RecordingResult {
  const RecordingResult({
    required this.outputPath,
    required this.durationMs,
    required this.themeId,
  });

  final String outputPath;
  final int durationMs;

  /// ID of the theme that was frozen at record-start. Stored with the clip.
  final String themeId;
}

/// Per-frame stats batch sent at ≤ 1 Hz via [EngineFlutterApi.onStats].
/// Never send per-frame data over the channel.
class FrameStats {
  const FrameStats({
    required this.fpsCurrent,
    required this.glRenderMs,
    required this.mlMs,
    required this.tier,
    required this.thermalStatus,
    required this.memoryPssMb,
  });

  final double fpsCurrent;
  final double glRenderMs;
  final double mlMs;
  final Tier tier;

  /// 0 = none, 1 = light, 2 = moderate, 3 = severe, 4 = critical (Android thermal API).
  final int thermalStatus;
  final int memoryPssMb;
}

/// Generic engine event. Use [type] to dispatch; [payload] is JSON for
/// type-specific data (scan %, error codes, theme stage, etc.).
class EngineEvent {
  const EngineEvent({
    required this.sessionId,
    required this.type,
    this.errorCode,
    this.payload,
  });

  final int sessionId;
  final EngineEventType type;
  final EngineErrorCode? errorCode;

  /// Optional JSON string for structured payloads (e.g. scan progress %).
  final String? payload;
}

// ─── APIs ────────────────────────────────────────────────────────────────────

/// Commands Dart → Native.
@HostApi()
abstract class EngineHostApi {
  /// Creates an EGL context, render thread, and registers a [SurfaceProducer]
  /// with Flutter's TextureRegistry. Returns [SessionInfo] with the textureId.
  @async
  SessionInfo createSession(SessionConfig config);

  /// Starts the CameraX preview pipeline and GL render loop.
  @async
  void startPreview(int sessionId);

  /// Stops the render loop and releases the camera. Does not destroy GL context.
  void stopPreview(int sessionId);

  /// Begins the 2–3 s guided room scan. Progress emitted via [EngineFlutterApi.onEvent].
  void startScan(int sessionId);

  /// Finalizes the scan, builds the scene descriptor, and returns it as JSON (<50 KB).
  @async
  String finishScan(int sessionId);

  /// Applies a downloaded theme bundle (located at [themeDir]) to the compositor.
  /// [stage] allows progressive application (base → textures → props).
  /// [crossfadeMs] duration for the crossfade animation.
  void applyTheme(
    int sessionId,
    String themeDir,
    ThemeStage stage,
    int crossfadeMs,
  );

  /// Debug / A/B: override the device tier. Pass null to revert to auto.
  void setTierOverride(int sessionId, Tier? tier);

  /// Starts encoding to [config.outputPath]. Freezes the current theme.
  @async
  void startRecording(int sessionId, RecordingConfig config);

  /// Stops encoding and muxes audio+video. Returns the file path and duration.
  @async
  RecordingResult stopRecording(int sessionId);

  /// Must release camera, GL context, codecs, and models within 500 ms.
  void disposeSession(int sessionId);
}

/// Events Native → Dart.
@FlutterApi()
abstract class EngineFlutterApi {
  /// Performance stats, batched and sent at ≤ 1 Hz.
  void onStats(FrameStats stats);

  /// Lifecycle and error events.
  void onEvent(EngineEvent event);
}
