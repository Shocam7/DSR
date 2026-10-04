/// Abstract interface for the DubSmash engine.
///
/// Production code uses the Pigeon-backed [PigeonEngineApi].
/// Tests use [FakeEngineApi] (in `test/`).
///
/// **This is the ONLY file in `lib/` that may call [EngineHostApi] methods.**
/// All other Dart code depends on this interface.
library;

import 'package:dsr_engine/dsr_engine.dart';

export 'package:dsr_engine/dsr_engine.dart'
    show
        EngineErrorCode,
        EngineEvent,
        EngineEventType,
        EngineFlutterApi,
        FrameStats,
        RecordingConfig,
        RecordingResult,
        SessionConfig,
        SessionInfo,
        ThemeStage,
        Tier;

/// The engine API surface exposed to the rest of the app.
/// Implementations must be provided via Riverpod (see [engineApiProvider]).
abstract interface class EngineApi {
  Future<SessionInfo> createSession(SessionConfig config);
  Future<void> startPreview(int sessionId);
  void stopPreview(int sessionId);
  Future<void> switchCamera(int sessionId);
  void startScan(int sessionId);
  Future<String> finishScan(int sessionId);
  void applyTheme(
    int sessionId,
    String themeDir,
    ThemeStage stage,
    int crossfadeMs,
  );
  void setTierOverride(int sessionId, Tier? tier);
  void setBackgroundEffect(
    int sessionId, {
    required bool enabled,
    required bool useBlur,
    required int colorArgb,
    required int blurRadius,
  });
  Future<void> startRecording(int sessionId, RecordingConfig config);
  Future<RecordingResult> stopRecording(int sessionId);
  Future<String> exportToGallery(String filePath);
  void disposeSession(int sessionId);
}
