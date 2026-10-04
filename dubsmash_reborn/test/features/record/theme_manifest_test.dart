// Copyright (c) 2026 DubSmash Reborn. All rights reserved.

import 'package:dubsmash_reborn/features/record/domain/theme_manifest.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('ThemeManifest Parser (M4-A1)', () {
    const validJson = '''
    {
      "schema": 1,
      "theme_id": "th_harrenhal_01",
      "universe": "uni_dark_medieval",
      "variant": "var_ruined_keep",
      "mode": "inspired",
      "surfaces": {
        "wall": {
          "albedo": "textures/wall_stone_a.ktx2",
          "normal": "textures/wall_stone_n.ktx2",
          "tile_m": 1.2
        },
        "floor": {
          "albedo": "textures/floor_cobble_a.ktx2",
          "tile_m": 0.8
        }
      },
      "props": [
        {
          "match": "bottle",
          "asset": "props/goblet_03.glb",
          "scale": 1.0
        }
      ],
      "atmosphere": {
        "lut": "luts/grade_ash.cube",
        "fog": 0.25,
        "particles": "embers_low"
      },
      "audio": {
        "ambience": "audio/hall_wind.ogg",
        "gain_db": -22.0
      },
      "stages": ["base", "textures", "props"],
      "expires": "2026-10-08T00:00:00Z"
    }
    ''';

    test('parses valid manifest completely', () {
      final manifest = ThemeManifest.parse(validJson);
      expect(manifest.schema, 1);
      expect(manifest.themeId, 'th_harrenhal_01');
      expect(manifest.universe, 'uni_dark_medieval');
      expect(manifest.variant, 'var_ruined_keep');
      expect(manifest.mode, 'inspired');
      expect(manifest.surfaces.length, 2);

      final wall = manifest.surfaces['wall']!;
      expect(wall.albedo, 'textures/wall_stone_a.ktx2');
      expect(wall.normal, 'textures/wall_stone_n.ktx2');
      expect(wall.tileM, 1.2);

      expect(manifest.props.length, 1);
      expect(manifest.props[0].match, 'bottle');
      expect(manifest.props[0].asset, 'props/goblet_03.glb');

      expect(manifest.atmosphere?.lut, 'luts/grade_ash.cube');
      expect(manifest.atmosphere?.fog, 0.25);
      expect(manifest.atmosphere?.particles, 'embers_low');

      expect(manifest.audio?.ambience, 'audio/hall_wind.ogg');
      expect(manifest.audio?.gainDb, -22.0);

      expect(manifest.stages, ['base', 'textures', 'props']);
      expect(manifest.expires, '2026-10-08T00:00:00Z');
    });

    test('rejects schema version mismatch', () {
      final futureJson = validJson.replaceAll('"schema": 1', '"schema": 2');
      expect(
        () => ThemeManifest.parse(futureJson),
        throwsA(isA<ThemeVersionException>()),
      );
    });

    test('rejects missing required fields', () {
      final missingFieldJson =
          validJson.replaceAll('"theme_id": "th_harrenhal_01",', '');
      expect(
        () => ThemeManifest.parse(missingFieldJson),
        throwsA(isA<ThemeValidationException>()),
      );
    });

    test('rejects path traversal in albedo', () {
      final attackJson = validJson.replaceAll(
          'textures/wall_stone_a.ktx2', '../../../../etc/passwd');
      expect(
        () => ThemeManifest.parse(attackJson),
        throwsA(isA<ThemeValidationException>()),
      );
    });

    test('rejects absolute paths', () {
      final attackJson = validJson.replaceAll(
          'textures/wall_stone_a.ktx2', '/sdcard/malicious.ktx2');
      expect(
        () => ThemeManifest.parse(attackJson),
        throwsA(isA<ThemeValidationException>()),
      );
    });

    test('rejects path traversal in prop asset', () {
      final attackJson = validJson.replaceAll(
          'props/goblet_03.glb', 'props/../../loot.glb');
      expect(
        () => ThemeManifest.parse(attackJson),
        throwsA(isA<ThemeValidationException>()),
      );
    });

    test('rejects null byte in path', () {
      final attackJson = validJson.replaceAll(
          'textures/wall_stone_a.ktx2', 'textures/wall\u0000.ktx2');
      expect(
        () => ThemeManifest.parse(attackJson),
        throwsA(isA<ThemeValidationException>()),
      );
    });

    test('rejects non-positive tile_m', () {
      final invalidTileJson =
          validJson.replaceAll('"tile_m": 1.2', '"tile_m": 0.0');
      expect(
        () => ThemeManifest.parse(invalidTileJson),
        throwsA(isA<ThemeValidationException>()),
      );
    });
  });
}
