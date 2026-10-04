// Copyright (c) 2026 DubSmash Reborn. All rights reserved.

import 'dart:convert';

class ThemeValidationException implements Exception {
  final String message;
  ThemeValidationException(this.message);

  @override
  String toString() => 'ThemeValidationException: $message';
}

class ThemeVersionException implements Exception {
  final int expectedVersion;
  final int actualVersion;

  ThemeVersionException(this.expectedVersion, this.actualVersion);

  @override
  String toString() =>
      'ThemeVersionException: expected $expectedVersion, found $actualVersion';
}

class SurfaceConfig {
  final String albedo;
  final String? normal;
  final double tileM;

  const SurfaceConfig({
    required this.albedo,
    this.normal,
    this.tileM = 1.0,
  });

  Map<String, dynamic> toJson() => {
        'albedo': albedo,
        if (normal != null) 'normal': normal,
        'tile_m': tileM,
      };
}

class PropConfig {
  final String match;
  final String asset;
  final double scale;

  const PropConfig({
    required this.match,
    required this.asset,
    this.scale = 1.0,
  });

  Map<String, dynamic> toJson() => {
        'match': match,
        'asset': asset,
        'scale': scale,
      };
}

class AtmosphereConfig {
  final String? lut;
  final double fog;
  final String? particles;

  const AtmosphereConfig({
    this.lut,
    this.fog = 0.0,
    this.particles,
  });

  Map<String, dynamic> toJson() => {
        if (lut != null) 'lut': lut,
        'fog': fog,
        if (particles != null) 'particles': particles,
      };
}

class AudioConfig {
  final String? ambience;
  final double gainDb;

  const AudioConfig({
    this.ambience,
    this.gainDb = 0.0,
  });

  Map<String, dynamic> toJson() => {
        if (ambience != null) 'ambience': ambience,
        'gain_db': gainDb,
      };
}

class ThemeManifest {
  static const int currentSchemaVersion = 1;

  final int schema;
  final String themeId;
  final String universe;
  final String variant;
  final String mode;
  final Map<String, SurfaceConfig> surfaces;
  final List<PropConfig> props;
  final AtmosphereConfig? atmosphere;
  final AudioConfig? audio;
  final List<String> stages;
  final String? expires;

  const ThemeManifest({
    required this.schema,
    required this.themeId,
    required this.universe,
    required this.variant,
    required this.mode,
    required this.surfaces,
    this.props = const [],
    this.atmosphere,
    this.audio,
    this.stages = const [],
    this.expires,
  });

  static ThemeManifest parse(String jsonString) {
    if (jsonString.trim().isEmpty) {
      throw ThemeValidationException('Manifest JSON cannot be empty');
    }

    final dynamic parsed;
    try {
      parsed = jsonDecode(jsonString);
    } catch (e) {
      throw ThemeValidationException('Malformed manifest JSON: $e');
    }

    if (parsed is! Map<String, dynamic>) {
      throw ThemeValidationException('Manifest root must be a JSON object');
    }

    if (!parsed.containsKey('schema')) {
      throw ThemeValidationException('Missing required field: schema');
    }

    final schemaVal = parsed['schema'];
    if (schemaVal is! int) {
      throw ThemeValidationException("Field 'schema' must be an integer");
    }
    if (schemaVal != currentSchemaVersion) {
      throw ThemeVersionException(currentSchemaVersion, schemaVal);
    }

    final themeId = _requireNonEmptyString(parsed, 'theme_id');
    final universe = _requireNonEmptyString(parsed, 'universe');
    final variant = _requireNonEmptyString(parsed, 'variant');
    final mode = _requireNonEmptyString(parsed, 'mode');

    // Surfaces
    final surfaces = <String, SurfaceConfig>{};
    if (parsed.containsKey('surfaces')) {
      final rawSurfaces = parsed['surfaces'];
      if (rawSurfaces is! Map<String, dynamic>) {
        throw ThemeValidationException("'surfaces' must be a JSON object");
      }
      for (final entry in rawSurfaces.entries) {
        final key = entry.key;
        final val = entry.value;
        if (val is! Map<String, dynamic>) {
          throw ThemeValidationException("Surface '$key' must be a JSON object");
        }
        final albedo = _requireSafeRelativePath(val, 'albedo', 'surfaces.$key.albedo');
        final normal = val.containsKey('normal')
            ? _requireSafeRelativePath(val, 'normal', 'surfaces.$key.normal')
            : null;
        final tileM = (val['tile_m'] as num?)?.toDouble() ?? 1.0;
        if (tileM <= 0.0) {
          throw ThemeValidationException("tile_m must be positive, got: $tileM for surface: $key");
        }
        surfaces[key] = SurfaceConfig(albedo: albedo, normal: normal, tileM: tileM);
      }
    }

    // Props
    final props = <PropConfig>[];
    if (parsed.containsKey('props')) {
      final rawProps = parsed['props'];
      if (rawProps is! List) {
        throw ThemeValidationException("'props' must be a JSON array");
      }
      for (var i = 0; i < rawProps.length; i++) {
        final item = rawProps[i];
        if (item is! Map<String, dynamic>) {
          throw ThemeValidationException('Prop at index $i must be a JSON object');
        }
        final match = _requireNonEmptyString(item, 'match');
        final asset = _requireSafeRelativePath(item, 'asset', 'props[$i].asset');
        final scale = (item['scale'] as num?)?.toDouble() ?? 1.0;
        props.add(PropConfig(match: match, asset: asset, scale: scale));
      }
    }

    // Atmosphere
    AtmosphereConfig? atmosphere;
    if (parsed.containsKey('atmosphere')) {
      final rawAtmo = parsed['atmosphere'];
      if (rawAtmo is! Map<String, dynamic>) {
        throw ThemeValidationException("'atmosphere' must be a JSON object");
      }
      final lut = rawAtmo.containsKey('lut')
          ? _requireSafeRelativePath(rawAtmo, 'lut', 'atmosphere.lut')
          : null;
      final fog = (rawAtmo['fog'] as num?)?.toDouble() ?? 0.0;
      final rawParticles = rawAtmo['particles'] as String?;
      String? particles;
      if (rawParticles != null && rawParticles.isNotEmpty) {
        _validateSafeFileName(rawParticles, 'atmosphere.particles');
        particles = rawParticles;
      }
      atmosphere = AtmosphereConfig(lut: lut, fog: fog, particles: particles);
    }

    // Audio
    AudioConfig? audio;
    if (parsed.containsKey('audio')) {
      final rawAudio = parsed['audio'];
      if (rawAudio is! Map<String, dynamic>) {
        throw ThemeValidationException("'audio' must be a JSON object");
      }
      final ambience = rawAudio.containsKey('ambience')
          ? _requireSafeRelativePath(rawAudio, 'ambience', 'audio.ambience')
          : null;
      final gainDb = (rawAudio['gain_db'] as num?)?.toDouble() ?? 0.0;
      audio = AudioConfig(ambience: ambience, gainDb: gainDb);
    }

    // Stages
    final stages = <String>[];
    if (parsed.containsKey('stages')) {
      final rawStages = parsed['stages'];
      if (rawStages is! List) {
        throw ThemeValidationException("'stages' must be a JSON array");
      }
      for (final stage in rawStages) {
        stages.add(stage.toString());
      }
    }

    final expires = parsed['expires'] as String?;

    return ThemeManifest(
      schema: schemaVal,
      themeId: themeId,
      universe: universe,
      variant: variant,
      mode: mode,
      surfaces: surfaces,
      props: props,
      atmosphere: atmosphere,
      audio: audio,
      stages: stages,
      expires: expires,
    );
  }

  static String _requireNonEmptyString(Map<String, dynamic> map, String key) {
    if (!map.containsKey(key)) {
      throw ThemeValidationException('Missing required field: $key');
    }
    final val = map[key];
    if (val is! String || val.trim().isEmpty) {
      throw ThemeValidationException("Field '$key' cannot be blank");
    }
    return val;
  }

  static String _requireSafeRelativePath(
      Map<String, dynamic> map, String key, String context) {
    final path = _requireNonEmptyString(map, key);
    validateSafeRelativePath(path, context);
    return path;
  }

  static void validateSafeRelativePath(String path, String context) {
    if (path.contains('\u0000')) {
      throw ThemeValidationException('Path traversal attempt (null byte) in $context: $path');
    }
    if (path.startsWith('/') || path.startsWith('\\')) {
      throw ThemeValidationException('Absolute paths are forbidden in $context: $path');
    }
    if (path.contains(':') && !path.startsWith('assets:')) {
      throw ThemeValidationException('Colon/protocol forbidden in path in $context: $path');
    }

    final segments = path.split(RegExp(r'[/\\]'));
    for (final segment in segments) {
      if (segment == '..') {
        throw ThemeValidationException('Path traversal attempt in $context: $path');
      }
    }
  }

  static void _validateSafeFileName(String name, String context) {
    if (name.contains('/') ||
        name.contains('\\') ||
        name.contains('..') ||
        name.contains('\u0000')) {
      throw ThemeValidationException('Invalid file name or traversal in $context: $name');
    }
  }
}
