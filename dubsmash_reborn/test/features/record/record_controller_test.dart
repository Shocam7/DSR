// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// M0 gate tests for RecordController using FakeEngineApi.
// These tests run without any native code.

import 'package:dubsmash_reborn/engine/engine_providers.dart';
import 'package:dubsmash_reborn/engine/fake_engine_api.dart';
import 'package:dubsmash_reborn/features/record/presentation/record_controller.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('RecordController (FakeEngineApi)', () {
    late ProviderContainer container;
    late FakeEngineApi fakeEngine;

    setUp(() {
      TestWidgetsFlutterBinding.ensureInitialized();
      fakeEngine = FakeEngineApi();
      container = ProviderContainer(
        overrides: [
          engineApiProvider.overrideWithValue(fakeEngine),
        ],
      );
    });

    tearDown(() => container.dispose());

    test('startSession creates session and returns textureId', () async {
      final notifier = container.read(recordControllerProvider.notifier);
      await notifier.startSession();

      final state = container.read(recordControllerProvider);
      expect(state.textureId, isNotNull);
      expect(fakeEngine.callLog, contains('createSession(1)'));
      expect(fakeEngine.callLog, contains('startPreview(1)'));
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
          overrides: [engineApiProvider.overrideWithValue(fakeEngine)],
        );
        final n = c.read(recordControllerProvider.notifier);
        await n.startSession();
        n.disposeSession();
        c.dispose();
      }
      // All 20 cycles completed without error.
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
  });
}
