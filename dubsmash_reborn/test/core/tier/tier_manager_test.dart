// Copyright (c) 2026 DubSmash Reborn. All rights reserved.

import 'package:dubsmash_reborn/core/tier/tier_manager.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('TierManager (M4-T7)', () {
    late TierManager tierManager;

    setUp(() {
      tierManager = TierManager();
    });

    test('resolves tier based on device rules (SM-M315F -> M, Redmi 9A -> L, Pixel 8 -> H)', () {
      expect(
        tierManager.resolveTier(deviceModel: 'Samsung SM-M315F'),
        DeviceTier.m,
      );
      expect(
        tierManager.resolveTier(deviceModel: 'Xiaomi Redmi 9A'),
        DeviceTier.l,
      );
      expect(
        tierManager.resolveTier(deviceModel: 'Google Pixel 8'),
        DeviceTier.h,
      );
    });

    test('resolves tier based on RAM thresholds when model is unknown', () {
      expect(
        tierManager.resolveTier(deviceModel: 'Generic Unknown Phone', totalRamMb: 2048),
        DeviceTier.l,
      );
      expect(
        tierManager.resolveTier(deviceModel: 'Generic Unknown Phone', totalRamMb: 4096),
        DeviceTier.m,
      );
      expect(
        tierManager.resolveTier(deviceModel: 'Generic Unknown Phone', totalRamMb: 8192),
        DeviceTier.h,
      );
    });

    test('provides correct tier properties for each tier', () {
      final tierLProps = tierManager.getPropertiesForTier(DeviceTier.l);
      expect(tierLProps.sceneSegResolution, 160);
      expect(tierLProps.sceneSegCadence, 4);
      expect(tierLProps.maxThemeMemoryMb, 8);
      expect(tierLProps.objectSwapEnabled, false);

      final tierMProps = tierManager.getPropertiesForTier(DeviceTier.m);
      expect(tierMProps.sceneSegResolution, 256);
      expect(tierMProps.sceneSegCadence, 3);
      expect(tierMProps.maxThemeMemoryMb, 16);
      expect(tierMProps.maxProps, 3);

      final tierHProps = tierManager.getPropertiesForTier(DeviceTier.h);
      expect(tierHProps.sceneSegResolution, 320);
      expect(tierHProps.sceneSegCadence, 2);
      expect(tierHProps.maxThemeMemoryMb, 25);
      expect(tierHProps.allowNormalMaps, true);
    });

    test('updates from Remote Config JSON and falls back safely on invalid JSON', () {
      const overrideJson = '''
      {
        "version": 2,
        "tiers": {
          "L": { "scene_seg_resolution": 128, "max_theme_memory_mb": 6 },
          "M": { "scene_seg_resolution": 240, "max_theme_memory_mb": 14 },
          "H": { "scene_seg_resolution": 360, "max_theme_memory_mb": 30 }
        },
        "device_rules": [
          { "patterns": ["CustomSpecialPhone"], "tier": "H" }
        ]
      }
      ''';

      tierManager.updateFromRemoteConfig(overrideJson);
      expect(
        tierManager.resolveTier(deviceModel: 'CustomSpecialPhone'),
        DeviceTier.h,
      );
      final props = tierManager.getPropertiesForTier(DeviceTier.h);
      expect(props.sceneSegResolution, 360);
      expect(props.maxThemeMemoryMb, 30);

      // Malformed remote config does not crash or corrupt
      tierManager.updateFromRemoteConfig('not a valid json {{{');
      expect(
        tierManager.resolveTier(deviceModel: 'CustomSpecialPhone'),
        DeviceTier.h,
      );
    });
  });
}
