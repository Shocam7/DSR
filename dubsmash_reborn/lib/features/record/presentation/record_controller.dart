// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// RecordController — Riverpod notifier that owns the engine session lifecycle,
// recording state machine, countdown, elapsed timer, audio calibration, and database indexing.
//
// Invariants enforced here:
//   - Only one session active at a time.
//   - disposeSession always called on widget dispose (via RecordScreen).
//   - Engine errors downgrade gracefully — they never crash the app.

import 'dart:async';
import 'dart:io';

import 'package:drift/drift.dart' hide isNotNull, isNull;
import 'package:file_picker/file_picker.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';

import '../../../core/database/app_database.dart';
import '../../../core/database/database_provider.dart';
import '../../../engine/engine_api.dart';
import '../../../engine/engine_providers.dart';
import '../domain/theme_manifest.dart';

// ─── State ────────────────────────────────────────────────────────────────────

/// Immutable state for the record screen.
final class RecordState {
  const RecordState({
    this.textureId,
    this.previewWidth = 1080,
    this.previewHeight = 1920,
    this.tier = Tier.m,
    this.isRecording = false,
    this.countdown = 0,
    this.recordingElapsedSeconds = 0,
    this.audioOffsetMs = 0,
    this.activeThemeId,
    this.activeThemeStage,
    this.audioRouteWarning,
    this.lastRecording,
    this.isFrontCamera = true,
    this.isPermissionsGranted = false,
    this.isPermanentlyDenied = false,
    this.isPaused = false,
    this.isCoverFit = true,
    this.selectedAudioPath,
    this.selectedAudioTitle,
    this.error,
    this.stats,
  });

  final int? textureId;
  final int previewWidth;
  final int previewHeight;
  final Tier tier;
  final bool isRecording;
  final int countdown;
  final int recordingElapsedSeconds;
  final int audioOffsetMs;
  final String? activeThemeId;
  final ThemeStage? activeThemeStage;
  final String? audioRouteWarning;
  final RecordingResult? lastRecording;
  final bool isFrontCamera;
  final bool isPermissionsGranted;
  final bool isPermanentlyDenied;
  final bool isPaused;
  final bool isCoverFit;
  final String? selectedAudioPath;
  final String? selectedAudioTitle;
  final EngineErrorCode? error;
  final FrameStats? stats;

  double get previewAspectRatio => previewWidth / previewHeight;

  RecordState copyWith({
    int? textureId,
    int? previewWidth,
    int? previewHeight,
    Tier? tier,
    bool? isRecording,
    int? countdown,
    int? recordingElapsedSeconds,
    int? audioOffsetMs,
    String? Function()? activeThemeId,
    ThemeStage? Function()? activeThemeStage,
    String? Function()? audioRouteWarning,
    RecordingResult? Function()? lastRecording,
    bool? isFrontCamera,
    bool? isPermissionsGranted,
    bool? isPermanentlyDenied,
    bool? isPaused,
    bool? isCoverFit,
    String? Function()? selectedAudioPath,
    String? Function()? selectedAudioTitle,
    EngineErrorCode? Function()? error,
    FrameStats? Function()? stats,
  }) =>
      RecordState(
        textureId: textureId ?? this.textureId,
        previewWidth: previewWidth ?? this.previewWidth,
        previewHeight: previewHeight ?? this.previewHeight,
        tier: tier ?? this.tier,
        isRecording: isRecording ?? this.isRecording,
        countdown: countdown ?? this.countdown,
        recordingElapsedSeconds:
            recordingElapsedSeconds ?? this.recordingElapsedSeconds,
        audioOffsetMs: audioOffsetMs ?? this.audioOffsetMs,
        activeThemeId:
            activeThemeId != null ? activeThemeId() : this.activeThemeId,
        activeThemeStage: activeThemeStage != null
            ? activeThemeStage()
            : this.activeThemeStage,
        audioRouteWarning: audioRouteWarning != null
            ? audioRouteWarning()
            : this.audioRouteWarning,
        lastRecording:
            lastRecording != null ? lastRecording() : this.lastRecording,
        isFrontCamera: isFrontCamera ?? this.isFrontCamera,
        isPermissionsGranted: isPermissionsGranted ?? this.isPermissionsGranted,
        isPermanentlyDenied: isPermanentlyDenied ?? this.isPermanentlyDenied,
        isPaused: isPaused ?? this.isPaused,
        isCoverFit: isCoverFit ?? this.isCoverFit,
        selectedAudioPath: selectedAudioPath != null
            ? selectedAudioPath()
            : this.selectedAudioPath,
        selectedAudioTitle: selectedAudioTitle != null
            ? selectedAudioTitle()
            : this.selectedAudioTitle,
        error: error != null ? error() : this.error,
        stats: stats != null ? stats() : this.stats,
      );
}

// ─── Notifier ─────────────────────────────────────────────────────────────────

class RecordController extends Notifier<RecordState> {
  static const int _sessionId = 1;
  static const String _storageKeyAudioOffset = 'dsr_audio_offset_ms';
  static const int maxRecordingSeconds = 60;

  EngineApi get _engine => ref.read(engineApiProvider);
  final FlutterSecureStorage _storage = const FlutterSecureStorage();

  Timer? _countdownTimer;
  Timer? _recordingTimer;
  Timer? _warningDismissTimer;

  @override
  RecordState build() {
    EngineFlutterApi.setUp(_EngineFlutterApiBridge(this));
    ref.onDispose(() {
      _countdownTimer?.cancel();
      _recordingTimer?.cancel();
      _warningDismissTimer?.cancel();
      EngineFlutterApi.setUp(null);
    });

    _loadSavedAudioOffset();

    return const RecordState();
  }

  Future<void> _loadSavedAudioOffset() async {
    try {
      final savedStr = await _storage.read(key: _storageKeyAudioOffset);
      if (savedStr != null) {
        final parsed = int.tryParse(savedStr);
        if (parsed != null) {
          state = state.copyWith(audioOffsetMs: parsed);
        }
      }
    } catch (_) {}
  }

  Future<void> setAudioOffset(int offsetMs) async {
    state = state.copyWith(audioOffsetMs: offsetMs);
    try {
      await _storage.write(
        key: _storageKeyAudioOffset,
        value: offsetMs.toString(),
      );
    } catch (_) {}
  }

  /// Verifies camera and microphone permissions before creating native session.
  Future<void> checkAndRequestPermissions() async {
    final cameraStatus = await Permission.camera.status;
    final micStatus = await Permission.microphone.status;

    if (cameraStatus.isGranted && micStatus.isGranted) {
      state = state.copyWith(
        isPermissionsGranted: true,
        isPermanentlyDenied: false,
        error: () => null,
      );
      await startSession();
      return;
    }

    final permissions = await [
      Permission.camera,
      Permission.microphone,
    ].request();

    final camResult = permissions[Permission.camera];
    final micResult = permissions[Permission.microphone];

    if (camResult == PermissionStatus.granted &&
        micResult == PermissionStatus.granted) {
      state = state.copyWith(
        isPermissionsGranted: true,
        isPermanentlyDenied: false,
        error: () => null,
      );
      await startSession();
    } else if (camResult == PermissionStatus.permanentlyDenied ||
        micResult == PermissionStatus.permanentlyDenied) {
      state = state.copyWith(
        isPermissionsGranted: false,
        isPermanentlyDenied: true,
        error: () => EngineErrorCode.permission,
      );
    } else {
      state = state.copyWith(
        isPermissionsGranted: false,
        isPermanentlyDenied: false,
        error: () => EngineErrorCode.permission,
      );
    }
  }

  Future<void> startSession() async {
    try {
      final info = await _engine.createSession(
        SessionConfig(
          sessionId: _sessionId,
          tier: Tier.m,
          isFrontCamera: state.isFrontCamera,
        ),
      );
      state = state.copyWith(
        textureId: info.textureId,
        previewWidth: info.previewWidth,
        previewHeight: info.previewHeight,
        tier: info.assignedTier,
        isPaused: false,
        error: () => null,
      );
      await _engine.startPreview(_sessionId);
    } on Object {
      state = state.copyWith(
        error: () => EngineErrorCode.permission,
      );
    }
  }

  /// M3: Relays the background effect settings to the native compositor.
  void applyBackgroundEffect({
    required bool enabled,
    required bool useBlur,
    required int colorArgb,
    required int blurRadius,
  }) {
    _engine.setBackgroundEffect(
      _sessionId,
      enabled: enabled,
      useBlur: useBlur,
      colorArgb: colorArgb,
      blurRadius: blurRadius,
    );
  }

  /// M4-T5: Applies a theme bundle from assets or disk with progressive staging.
  Future<void> applyThemeBundle({
    required String themeAssetDir,
    ThemeStage stage = ThemeStage.textures,
    int crossfadeMs = 400,
  }) async {
    try {
      final docDir = await getApplicationDocumentsDirectory();
      final targetDir = Directory('${docDir.path}/$themeAssetDir');

      // Copy theme assets from bundle if not already extracted or outdated
      if (!await targetDir.exists()) {
        await targetDir.create(recursive: true);
        await Directory('${targetDir.path}/textures').create(recursive: true);
      }

      // Read and extract manifest.json
      final manifestJsonStr =
          await rootBundle.loadString('$themeAssetDir/manifest.json');
      final manifestFile = File('${targetDir.path}/manifest.json');
      await manifestFile.writeAsString(manifestJsonStr);

      // Extract textures
      final manifest = ThemeManifest.parse(manifestJsonStr);
      for (final entry in manifest.surfaces.entries) {
        final albedoPath = entry.value.albedo;
        try {
          final byteData =
              await rootBundle.load('$themeAssetDir/$albedoPath');
          final outFile = File('${targetDir.path}/$albedoPath');
          await outFile.parent.create(recursive: true);
          await outFile.writeAsBytes(byteData.buffer.asUint8List(
            byteData.offsetInBytes,
            byteData.lengthInBytes,
          ));
        } catch (_) {}

        // Also extract ETC2 fallback if present
        final etc2Path = albedoPath.replaceAll('.ktx2', '.etc2.ktx2');
        try {
          final byteData =
              await rootBundle.load('$themeAssetDir/$etc2Path');
          final outFile = File('${targetDir.path}/$etc2Path');
          await outFile.parent.create(recursive: true);
          await outFile.writeAsBytes(byteData.buffer.asUint8List(
            byteData.offsetInBytes,
            byteData.lengthInBytes,
          ));
        } catch (_) {}
      }

      _engine.applyTheme(
        _sessionId,
        targetDir.path,
        stage,
        crossfadeMs,
      );

      state = state.copyWith(
        activeThemeId: () => manifest.themeId,
        activeThemeStage: () => stage,
      );
    } catch (e, st) {
      // ignore: avoid_print
      print('DEBUG applyThemeBundle error: $e\n$st');
      state = state.copyWith(
        error: () => EngineErrorCode.themeInvalid,
      );
    }
  }

  /// Clears active theme surfaces.
  void clearTheme() {
    _engine.applyTheme(_sessionId, '', ThemeStage.base, 0);
    state = state.copyWith(
      activeThemeId: () => null,
      activeThemeStage: () => null,
    );
  }

  Future<void> switchCamera() async {
    if (state.textureId == null || state.isRecording) return;
    try {
      await _engine.switchCamera(_sessionId);
      state = state.copyWith(
        isFrontCamera: !state.isFrontCamera,
      );
    } on Object {
      state = state.copyWith(
        error: () => EngineErrorCode.cameraBusy,
      );
    }
  }

  /// Toggles pause / resume of the camera preview.
  Future<void> togglePause() async {
    if (state.textureId == null || state.isRecording) return;
    if (state.isPaused) {
      await resumePreview();
    } else {
      stopPreview();
    }
  }

  /// Toggles between full-screen cover fit and exact 9:16 contain fit.
  void toggleFitMode() {
    state = state.copyWith(isCoverFit: !state.isCoverFit);
  }

  void stopPreview() {
    _engine.stopPreview(_sessionId);
    state = state.copyWith(isPaused: true);
  }

  Future<void> resumePreview() async {
    if (state.textureId != null) {
      await _engine.startPreview(_sessionId);
      state = state.copyWith(isPaused: false);
    }
  }

  // ── Recording Flow (M2-T1, M2-T2, M2-T5) ──────────────────────────────────

  /// Starts 3-second countdown before recording.
  void startCountdownAndRecord() {
    if (state.isRecording || state.countdown > 0) return;

    state = state.copyWith(countdown: 3);
    _countdownTimer?.cancel();
    _countdownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      final next = state.countdown - 1;
      if (next <= 0) {
        timer.cancel();
        state = state.copyWith(countdown: 0);
        startRecording();
      } else {
        state = state.copyWith(countdown: next);
      }
    });
  }

  /// Cancels countdown if user taps to cancel.
  void cancelCountdown() {
    _countdownTimer?.cancel();
    state = state.copyWith(countdown: 0);
  }

  /// Allows user to pick an audio file (MP3, AAC, WAV, M4A) to play as background track.
  Future<void> selectAudioFile() async {
    try {
      final result = await FilePicker.pickFiles(
        type: FileType.audio,
        allowMultiple: false,
      );
      if (result != null && result.files.isNotEmpty) {
        final picked = result.files.single;
        final origPath = picked.path;
        if (origPath != null) {
          // Copy to internal documents directory to ensure 100% native access without scoped storage restrictions
          final dir = await getApplicationDocumentsDirectory();
          final soundsDir = Directory('${dir.path}/sounds');
          if (!await soundsDir.exists()) {
            await soundsDir.create(recursive: true);
          }
          final ext = origPath.contains('.') ? origPath.split('.').last : 'mp3';
          final localFile = File(
            '${soundsDir.path}/user_audio_${DateTime.now().millisecondsSinceEpoch}.$ext',
          );
          await File(origPath).copy(localFile.path);

          state = state.copyWith(
            selectedAudioPath: () => localFile.path,
            selectedAudioTitle: () => picked.name,
          );
        }
      }
    } catch (_) {}
  }

  /// Sets a preset audio track from assets by extracting it to local storage.
  Future<void> selectPresetAudioTrack(String assetPath, String title) async {
    try {
      final byteData = await rootBundle.load(assetPath);
      final dir = await getApplicationDocumentsDirectory();
      final soundsDir = Directory('${dir.path}/sounds');
      if (!await soundsDir.exists()) {
        await soundsDir.create(recursive: true);
      }
      final filename = assetPath.split('/').last;
      final file = File('${soundsDir.path}/$filename');
      await file.writeAsBytes(
        byteData.buffer.asUint8List(
          byteData.offsetInBytes,
          byteData.lengthInBytes,
        ),
      );

      state = state.copyWith(
        selectedAudioPath: () => file.path,
        selectedAudioTitle: () => title,
      );
    } catch (_) {
      state = state.copyWith(
        selectedAudioPath: () => assetPath,
        selectedAudioTitle: () => title,
      );
    }
  }

  /// Sets a specific background audio track by path and title (e.g. sample presets).
  void setAudioTrack(String path, String title) {
    state = state.copyWith(
      selectedAudioPath: () => path,
      selectedAudioTitle: () => title,
    );
  }

  /// Removes the currently selected background audio track.
  void clearSelectedAudio() {
    state = state.copyWith(
      selectedAudioPath: () => null,
      selectedAudioTitle: () => null,
    );
  }

  /// Starts encoding to disk via native MediaCodec surface encoder.
  Future<void> startRecording({String? customOutputPath}) async {
    if (state.isRecording || state.textureId == null) return;

    try {
      String outputPath = customOutputPath ?? '';
      if (outputPath.isEmpty) {
        final dir = await getApplicationDocumentsDirectory();
        final ts = DateTime.now().millisecondsSinceEpoch;
        outputPath = '${dir.path}/recording_$ts.mp4';
      }

      await _engine.startRecording(
        _sessionId,
        RecordingConfig(
          outputPath: outputPath,
          width: state.previewWidth,
          height: state.previewHeight,
          videoBitrateBps: state.tier == Tier.l ? 5000000 : 10000000,
          audioOffsetMs: state.audioOffsetMs,
          audioTrackPath: state.selectedAudioPath,
        ),
      );

      state = state.copyWith(
        isRecording: true,
        recordingElapsedSeconds: 0,
        error: () => null,
      );

      _recordingTimer?.cancel();
      _recordingTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
        final elapsed = state.recordingElapsedSeconds + 1;
        state = state.copyWith(recordingElapsedSeconds: elapsed);
        if (elapsed >= maxRecordingSeconds) {
          stopRecording();
        }
      });
    } on Object {
      state = state.copyWith(
        isRecording: false,
        error: () => EngineErrorCode.encoderFailed,
      );
    }
  }

  /// Stops recording, persists metadata to Drift database, and prepares result.
  Future<RecordingResult?> stopRecording() async {
    if (!state.isRecording) return null;

    _recordingTimer?.cancel();
    state = state.copyWith(isRecording: false);

    try {
      final result = await _engine.stopRecording(_sessionId);

      // Save into Drift local database
      final db = ref.read(databaseProvider);
      final id = 'rec_${DateTime.now().millisecondsSinceEpoch}';
      await db.insertRecording(RecordingsCompanion.insert(
        id: id,
        filePath: result.outputPath,
        durationMs: result.durationMs,
        themeId: result.themeId,
        createdAt: DateTime.now(),
        trimStartMs: const Value(0),
        trimEndMs: Value(result.durationMs),
        audioOffsetMs: Value(state.audioOffsetMs),
      ));

      // Automatically export and index into device gallery (DCIM/Dubsmash)
      try {
        await _engine.exportToGallery(result.outputPath);
      } catch (_) {}

      state = state.copyWith(
        lastRecording: () => result,
      );
      return result;
    } on Object {
      state = state.copyWith(
        error: () => EngineErrorCode.encoderFailed,
      );
      return null;
    }
  }

  /// Invoked when app goes to background.
  void onAppPaused() {
    if (state.isRecording) {
      stopRecording();
    }
    if (state.textureId != null && !state.isPaused) {
      _engine.stopPreview(_sessionId);
      state = state.copyWith(isPaused: true);
    }
  }

  /// Invoked when app returns to foreground.
  Future<void> onAppResumed() async {
    if (state.textureId != null && state.isPaused) {
      await _engine.startPreview(_sessionId);
      state = state.copyWith(isPaused: false);
    }
  }

  void disposeSession() {
    _countdownTimer?.cancel();
    _recordingTimer?.cancel();
    _warningDismissTimer?.cancel();
    if (state.isRecording) {
      stopRecording();
    }
    _engine.disposeSession(_sessionId);
  }

  /// Called from [EngineFlutterApi.onStats] via the engine event bridge.
  void onStats(FrameStats stats) {
    state = state.copyWith(stats: () => stats, tier: stats.tier);
  }

  /// Called from [EngineFlutterApi.onEvent] via the engine event bridge.
  void onEvent(EngineEvent event) {
    if (event.type == EngineEventType.error && event.errorCode != null) {
      state = state.copyWith(error: () => event.errorCode);
    }

    // Audio route changes mid-session (Bluetooth / headphones disconnect - M2-T6)
    if (event.payload != null &&
        event.payload!.contains('audio_route_changed')) {
      final warningMsg = event.payload!.contains('"disconnected":true')
          ? 'Headphones disconnected mid-session: switched to speaker with echo cancellation.'
          : 'Audio route changed.';
      state = state.copyWith(audioRouteWarning: () => warningMsg);

      _warningDismissTimer?.cancel();
      _warningDismissTimer = Timer(const Duration(seconds: 5), () {
        state = state.copyWith(audioRouteWarning: () => null);
      });
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
