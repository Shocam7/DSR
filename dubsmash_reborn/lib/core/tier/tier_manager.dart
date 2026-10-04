// Copyright (c) 2026 DubSmash Reborn. All rights reserved.

import 'dart:convert';
import 'package:flutter/services.dart';

enum DeviceTier { l, m, h }

class TierProperties {
  final int sceneSegResolution;
  final int sceneSegCadence;
  final bool objectSwapEnabled;
  final int maxProps;
  final bool depthEnabled;
  final int themeTextureMaxSize;
  final int maxThemeMemoryMb;
  final bool allowNormalMaps;
  final String particlesLevel;

  const TierProperties({
    required this.sceneSegResolution,
    required this.sceneSegCadence,
    required this.objectSwapEnabled,
    required this.maxProps,
    required this.depthEnabled,
    required this.themeTextureMaxSize,
    required this.maxThemeMemoryMb,
    required this.allowNormalMaps,
    required this.particlesLevel,
  });

  factory TierProperties.fromJson(Map<String, dynamic> json) {
    return TierProperties(
      sceneSegResolution: json['scene_seg_resolution'] as int? ?? 256,
      sceneSegCadence: json['scene_seg_cadence'] as int? ?? 3,
      objectSwapEnabled: json['object_swap_enabled'] as bool? ?? false,
      maxProps: json['max_props'] as int? ?? 0,
      depthEnabled: json['depth_enabled'] as bool? ?? false,
      themeTextureMaxSize: json['theme_texture_max_size'] as int? ?? 1024,
      maxThemeMemoryMb: json['max_theme_memory_mb'] as int? ?? 16,
      allowNormalMaps: json['allow_normal_maps'] as bool? ?? false,
      particlesLevel: json['particles_level'] as String? ?? 'moderate',
    );
  }
}

class TierManager {
  static const String bundledConfigPath = 'assets/config/tier_table.json';

  Map<String, dynamic>? _config;

  TierManager([Map<String, dynamic>? initialConfig]) : _config = initialConfig;

  Future<void> loadBundledConfig([AssetBundle? bundle]) async {
    final assetBundle = bundle ?? rootBundle;
    try {
      final jsonString = await assetBundle.loadString(bundledConfigPath);
      _config = jsonDecode(jsonString) as Map<String, dynamic>;
    } catch (e) {
      // Fallback in-memory default
      _config = _defaultFallbackConfig;
    }
  }

  void updateFromRemoteConfig(String? remoteJson) {
    if (remoteJson == null || remoteJson.trim().isEmpty) return;
    try {
      final parsed = jsonDecode(remoteJson);
      if (parsed is Map<String, dynamic> && parsed.containsKey('tiers')) {
        _config = parsed;
      }
    } catch (_) {
      // Retain existing config on malformed remote config
    }
  }

  DeviceTier resolveTier({
    required String deviceModel,
    int? totalRamMb,
  }) {
    final config = _config ?? _defaultFallbackConfig;
    final rules = (config['device_rules'] as List<dynamic>?) ?? [];

    for (final rule in rules) {
      if (rule is Map<String, dynamic>) {
        final patterns = (rule['patterns'] as List<dynamic>?) ?? [];
        final tierStr = rule['tier'] as String?;
        for (final p in patterns) {
          if (deviceModel.toLowerCase().contains(p.toString().toLowerCase())) {
            return _parseTierString(tierStr);
          }
        }
      }
    }

    if (totalRamMb != null && totalRamMb > 0) {
      final ramThresholds = config['ram_thresholds_mb'] as Map<String, dynamic>?;
      final tierLMax = (ramThresholds?['tier_l_max'] as num?)?.toInt() ?? 3072;
      final tierMMax = (ramThresholds?['tier_m_max'] as num?)?.toInt() ?? 6144;

      if (totalRamMb <= tierLMax) {
        return DeviceTier.l;
      } else if (totalRamMb <= tierMMax) {
        return DeviceTier.m;
      } else {
        return DeviceTier.h;
      }
    }

    final defaultTierStr = config['default_tier'] as String? ?? 'M';
    return _parseTierString(defaultTierStr);
  }

  TierProperties getPropertiesForTier(DeviceTier tier) {
    final config = _config ?? _defaultFallbackConfig;
    final tiersMap = config['tiers'] as Map<String, dynamic>? ?? {};
    final key = tier == DeviceTier.l ? 'L' : (tier == DeviceTier.h ? 'H' : 'M');
    final tierJson = tiersMap[key] as Map<String, dynamic>? ?? {};
    return TierProperties.fromJson(tierJson);
  }

  DeviceTier _parseTierString(String? tierStr) {
    switch (tierStr?.toUpperCase()) {
      case 'L':
        return DeviceTier.l;
      case 'H':
        return DeviceTier.h;
      case 'M':
      default:
        return DeviceTier.m;
    }
  }

  static const Map<String, dynamic> _defaultFallbackConfig = {
    "version": 1,
    "tiers": {
      "L": {
        "scene_seg_resolution": 160,
        "scene_seg_cadence": 4,
        "object_swap_enabled": false,
        "max_props": 0,
        "depth_enabled": false,
        "theme_texture_max_size": 512,
        "max_theme_memory_mb": 8,
        "allow_normal_maps": false,
        "particles_level": "minimal"
      },
      "M": {
        "scene_seg_resolution": 256,
        "scene_seg_cadence": 3,
        "object_swap_enabled": true,
        "max_props": 3,
        "depth_enabled": false,
        "theme_texture_max_size": 1024,
        "max_theme_memory_mb": 16,
        "allow_normal_maps": false,
        "particles_level": "moderate"
      },
      "H": {
        "scene_seg_resolution": 320,
        "scene_seg_cadence": 2,
        "object_swap_enabled": true,
        "max_props": 8,
        "depth_enabled": true,
        "theme_texture_max_size": 1024,
        "max_theme_memory_mb": 25,
        "allow_normal_maps": true,
        "particles_level": "full"
      }
    },
    "device_rules": [
      {
        "patterns": ["Redmi 9A", "SM-A032F", "moto e20", "CPH2185", "CPH2179"],
        "tier": "L"
      },
      {
        "patterns": ["SM-M315F", "Pixel 4a", "Redmi Note 10", "SM-A525F", "CPH2219"],
        "tier": "M"
      },
      {
        "patterns": ["Pixel 7", "Pixel 8", "Pixel 9", "SM-S901B", "SM-S918B", "SM-S928B"],
        "tier": "H"
      }
    ],
    "ram_thresholds_mb": {
      "tier_l_max": 3072,
      "tier_m_max": 6144
    },
    "default_tier": "M"
  };
}
