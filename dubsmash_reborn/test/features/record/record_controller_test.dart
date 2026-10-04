// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// M0, M1 & M2 tests for RecordController using FakeEngineApi and in-memory AppDatabase.

import 'package:drift/native.dart';
import 'package:dubsmash_reborn/core/database/app_database.dart';
import 'package:dubsmash_reborn/core/database/database_provider.dart';
import 'package:dubsmash_reborn/engine/engine_api.dart';
import 'package:dubsmash_reborn/engine/engine_providers.dart';
import 'package:dubsmash_reborn/engine/fake_engine_api.dart';
import 'package:dubsmash_reborn/features/record/presentation/record_controller.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('RecordController (FakeEngineApi)', () {
    late ProviderContainer container;
    late FakeEngineApi fakeEngine;
    late AppDatabase testDb;

    setUp(() {
      TestWidgetsFlutterBinding.ensureInitialized();
      fakeEngine = FakeEngineApi();
      testDb = AppDatabase(NativeDatabase.memory());
      container = ProviderContainer(
        overrides: [
          engineApiProvider.overrideWithValue(fakeEngine),
          databaseProvider.overrideWithValue(testDb),
        ],
      );
    });

    tearDown(() async {
      container.dispose();
      await testDb.close();
    });

    test('startSession creates session and returns textureId', () async {
      final notifier = container.read(recordControllerProvider.notifier);
      await notifier.startSession();

      final state = container.read(recordControllerProvider);
      expect(state.textureId, isNotNull);
      expect(state.isFrontCamera, isTrue);
      expect(fakeEngine.callLog, contains('createSession(1)'));
      expect(fakeEngine.callLog, contains('startPreview(1)'));
    });

    test('switchCamera toggles isFrontCamera and calls engine', () async {
      final notifier = container.read(recordControllerProvider.notifier);
      await notifier.startSession();

      expect(container.read(recordControllerProvider).isFrontCamera, isTrue);
      await notifier.switchCamera();

      expect(container.read(recordControllerProvider).isFrontCamera, isFalse);
      expect(fakeEngine.callLog, contains('switchCamera(1)'));

      await notifier.switchCamera();
      expect(container.read(recordControllerProvider).isFrontCamera, isTrue);
    });

    test('disposeSession calls engine dispose', () async {
      final notifier = container.read(recordControllerProvider.notifier);
      await notifier.startSession();
      notifier.disposeSession();

      expect(fakeEngine.callLog, contains('disposeSession(1)'));
    });

    test('stopPreview and resumePreview round-trip', () async {
      final notifier = container.read(recordControllerProvider.notifier);
      await notifier.startSession();
      notifier.stopPreview();
      await notifier.resumePreview();

      expect(fakeEngine.callLog, containsAllInOrder([
        'startPreview(1)',
        'stopPreview(1)',
        'startPreview(1)',
      ]));
    });

    test('20 session create/dispose cycles leave no state', () async {
      for (var i = 0; i < 20; i++) {
        final c = ProviderContainer(
          overrides: [
            engineApiProvider.overrideWithValue(fakeEngine),
            databaseProvider.overrideWithValue(testDb),
          ],
        );
        final n = c.read(recordControllerProvider.notifier);
        await n.startSession();
        n.disposeSession();
        c.dispose();
      }
      expect(
        fakeEngine.callLog.where((l) => l.startsWith('createSession')).length,
        equals(20),
      );
    });

    test('previewAspectRatio is correct', () async {
      fakeEngine.previewWidth = 1080;
      fakeEngine.previewHeight = 1920;
      final notifier = container.read(recordControllerProvider.notifier);
      await notifier.startSession();

      final state = container.read(recordControllerProvider);
      expect(state.previewAspectRatio, closeTo(1080 / 1920, 0.001));
    });

    test('onStats updates frame stats and tier', () {
      final notifier = container.read(recordControllerProvider.notifier);
      final stats = FrameStats(
        fpsCurrent: 30.5,
        glRenderMs: 11.2,
        mlMs: 0.0,
        tier: Tier.m,
        thermalStatus: 0,
        memoryPssMb: 180,
      );

      notifier.onStats(stats);

      final state = container.read(recordControllerProvider);
      expect(state.stats, equals(stats));
      expect(state.tier, equals(Tier.m));
      expect(container.read(frameStatsProvider), equals(stats));
    });

    test('onEvent handles error codes gracefully', () {
      final notifier = container.read(recordControllerProvider.notifier);
      final event = EngineEvent(
        sessionId: 1,
        type: EngineEventType.error,
        errorCode: EngineErrorCode.cameraBusy,
      );

      notifier.onEvent(event);

      final state = container.read(recordControllerProvider);
      expect(state.error, equals(EngineErrorCode.cameraBusy));
    });

    test('togglePause toggles isPaused and stops/resumes preview', () async {
      final notifier = container.read(recordControllerProvider.notifier);
      await notifier.startSession();

      expect(container.read(recordControllerProvider).isPaused, isFalse);

      await notifier.togglePause();
      expect(container.read(recordControllerProvider).isPaused, isTrue);
      expect(fakeEngine.callLog, contains('stopPreview(1)'));

      await notifier.togglePause();
      expect(container.read(recordControllerProvider).isPaused, isFalse);
      expect(fakeEngine.callLog.where((l) => l == 'startPreview(1)').length,
          equals(2));
    });

    test('toggleFitMode toggles between cover and contain', () {
      final notifier = container.read(recordControllerProvider.notifier);
      expect(container.read(recordControllerProvider).isCoverFit, isTrue);

      notifier.toggleFitMode();
      expect(container.read(recordControllerProvider).isCoverFit, isFalse);

      notifier.toggleFitMode();
      expect(container.read(recordControllerProvider).isCoverFit, isTrue);
    });

    test('onAppPaused and onAppResumed manage preview lifecycle', () async {
      final notifier = container.read(recordControllerProvider.notifier);
      await notifier.startSession();

      notifier.onAppPaused();
      expect(container.read(recordControllerProvider).isPaused, isTrue);
      expect(fakeEngine.callLog, contains('stopPreview(1)'));

      await notifier.onAppResumed();
      expect(container.read(recordControllerProvider).isPaused, isFalse);
      expect(fakeEngine.callLog.where((l) => l == 'startPreview(1)').length,
          equals(2));
    });

    // ── M2 Tests ─────────────────────────────────────────────────────────────

    test('startRecording and stopRecording persists to Drift database (M2-T1, M2-T5)', () async {
      final notifier = container.read(recordControllerProvider.notifier);
      await notifier.startSession();

      expect(container.read(recordControllerProvider).isRecording, isFalse);

      await notifier.startRecording(customOutputPath: '/test/output.mp4');
      expect(container.read(recordControllerProvider).isRecording, isTrue);
      expect(fakeEngine.callLog.any((l) => l.startsWith('startRecording(1, /test/output.mp4)')), isTrue);

      final result = await notifier.stopRecording();
      expect(container.read(recordControllerProvider).isRecording, isFalse);
      expect(result, isNotNull);
      expect(fakeEngine.callLog, contains('stopRecording(1)'));

      // Verify Drift database record was written
      final recordings = await testDb.getAllRecordings();
      expect(recordings.length, 1);
      expect(recordings.first.filePath, result!.outputPath);
      expect(recordings.first.durationMs, result.durationMs);
    });

    test('setAudioOffset updates state (M2-T4)', () async {
      final notifier = container.read(recordControllerProvider.notifier);
      expect(container.read(recordControllerProvider).audioOffsetMs, 0);

      await notifier.setAudioOffset(45);
      expect(container.read(recordControllerProvider).audioOffsetMs, 45);

      await notifier.setAudioOffset(-30);
      expect(container.read(recordControllerProvider).audioOffsetMs, -30);
    });

    test('onEvent handles mid-session audio route change warning (M2-T6)', () {
      final notifier = container.read(recordControllerProvider.notifier);
      expect(container.read(recordControllerProvider).audioRouteWarning, isNull);

      final routeEvent = EngineEvent(
        sessionId: 1,
        type: EngineEventType.error,
        payload: '{"event":"audio_route_changed","is_headphones":false,"disconnected":true}',
      );

      notifier.onEvent(routeEvent);

      final state = container.read(recordControllerProvider);
      expect(state.audioRouteWarning, isNotNull);
      expect(state.audioRouteWarning, contains('Headphones disconnected'));
    });

    test('setAudioTrack and clearSelectedAudio manage audio state', () {
      final notifier = container.read(recordControllerProvider.notifier);
      expect(container.read(recordControllerProvider).selectedAudioPath, isNull);
      expect(container.read(recordControllerProvider).selectedAudioTitle, isNull);

      notifier.setAudioTrack('/sdcard/Music/beat.mp3', 'beat.mp3');
      expect(
        container.read(recordControllerProvider).selectedAudioPath,
        '/sdcard/Music/beat.mp3',
      );
      expect(
        container.read(recordControllerProvider).selectedAudioTitle,
        'beat.mp3',
      );

      notifier.clearSelectedAudio();
      expect(container.read(recordControllerProvider).selectedAudioPath, isNull);
      expect(container.read(recordControllerProvider).selectedAudioTitle, isNull);
    });

    test('startRecording propagates selectedAudioPath to RecordingConfig', () async {
      final notifier = container.read(recordControllerProvider.notifier);
      await notifier.startSession();

      notifier.setAudioTrack('/sdcard/Music/groove.mp3', 'groove.mp3');
      await notifier.startRecording(customOutputPath: '/test/audio_mixed.mp4');

      expect(fakeEngine.lastRecordingConfig, isNotNull);
      expect(fakeEngine.lastRecordingConfig!.audioTrackPath, '/sdcard/Music/groove.mp3');

      await notifier.stopRecording();
    });

    test('clearTheme resets theme state', () {
      final notifier = container.read(recordControllerProvider.notifier);
      notifier.clearTheme();

      final state = container.read(recordControllerProvider);
      expect(state.activeThemeId, isNull);
      expect(state.activeThemeStage, isNull);
    });
  });
}
