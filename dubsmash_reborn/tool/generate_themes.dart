// Copyright (c) 2026 DubSmash Reborn. All rights reserved.

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

void main() {
  final baseDir = Directory('assets/themes');
  if (!baseDir.existsSync()) {
    baseDir.createSync(recursive: true);
  }

  // 1. Base Look Pack
  createThemePack(
    themeDir: 'assets/themes/base_look',
    manifestJson: {
      "schema": 1,
      "theme_id": "th_base_look",
      "universe": "uni_clean",
      "variant": "var_neutral",
      "mode": "inspired",
      "surfaces": {
        "wall": {
          "albedo": "textures/wall_neutral_a.ktx2",
          "tile_m": 1.0
        },
        "floor": {
          "albedo": "textures/floor_neutral_a.ktx2",
          "tile_m": 1.0
        }
      },
      "props": [],
      "atmosphere": {
        "fog": 0.0
      },
      "stages": ["base", "textures"]
    },
    textures: {
      "textures/wall_neutral_a.ktx2": 0xD0,
      "textures/wall_neutral_a.etc2.ktx2": 0xD0,
      "textures/floor_neutral_a.ktx2": 0x90,
      "textures/floor_neutral_a.etc2.ktx2": 0x90,
    },
  );

  // 2. Test Theme 1: Medieval Stone
  createThemePack(
    themeDir: 'assets/themes/medieval_stone',
    manifestJson: {
      "schema": 1,
      "theme_id": "th_medieval_stone",
      "universe": "uni_dark_medieval",
      "variant": "var_ruined_keep",
      "mode": "inspired",
      "surfaces": {
        "wall": {
          "albedo": "textures/wall_stone_a.ktx2",
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
        "fog": 0.25,
        "particles": "embers_low"
      },
      "audio": {
        "ambience": "audio/hall_wind.ogg",
        "gain_db": -22.0
      },
      "stages": ["base", "textures", "props"]
    },
    textures: {
      "textures/wall_stone_a.ktx2": 0x6A,
      "textures/wall_stone_a.etc2.ktx2": 0x6A,
      "textures/floor_cobble_a.ktx2": 0x48,
      "textures/floor_cobble_a.etc2.ktx2": 0x48,
    },
  );

  // 3. Test Theme 2: Cyberpunk Neon
  createThemePack(
    themeDir: 'assets/themes/cyberpunk_neon',
    manifestJson: {
      "schema": 1,
      "theme_id": "th_cyberpunk_neon",
      "universe": "uni_scifi_cyber",
      "variant": "var_cyber_alley",
      "mode": "inspired",
      "surfaces": {
        "wall": {
          "albedo": "textures/wall_metal_a.ktx2",
          "tile_m": 1.5
        },
        "floor": {
          "albedo": "textures/floor_grid_a.ktx2",
          "tile_m": 1.0
        }
      },
      "props": [
        {
          "match": "lamp",
          "asset": "props/neon_sign.glb",
          "scale": 1.2
        }
      ],
      "atmosphere": {
        "fog": 0.15,
        "particles": "rain_light"
      },
      "audio": {
        "ambience": "audio/cyber_drone.ogg",
        "gain_db": -18.0
      },
      "stages": ["base", "textures", "props"]
    },
    textures: {
      "textures/wall_metal_a.ktx2": 0x22,
      "textures/wall_metal_a.etc2.ktx2": 0x22,
      "textures/floor_grid_a.ktx2": 0x1A,
      "textures/floor_grid_a.etc2.ktx2": 0x1A,
    },
  );

  print('All 3 themes (Base Look, Medieval Stone, Cyberpunk Neon) successfully generated!');
}

void createThemePack({
  required String themeDir,
  required Map<String, dynamic> manifestJson,
  required Map<String, int> textures,
}) {
  final dir = Directory(themeDir);
  if (!dir.existsSync()) dir.createSync(recursive: true);

  // Write manifest.json
  final manifestFile = File('${dir.path}/manifest.json');
  manifestFile.writeAsStringSync(const JsonEncoder.withIndent('  ').convert(manifestJson));

  // Write textures
  for (final entry in textures.entries) {
    final textureFile = File('${dir.path}/${entry.key}');
    if (!textureFile.parent.existsSync()) {
      textureFile.parent.createSync(recursive: true);
    }
    final isEtc2 = entry.key.contains('.etc2.');
    final vkFormat = isEtc2 ? 147 : 157; // 147 = ETC2_R8G8B8A8, 157 = ASTC_4x4
    final ktx2Bytes = buildKtx2(
      width: 128,
      height: 128,
      vkFormat: vkFormat,
      fillByte: entry.value,
    );
    textureFile.writeAsBytesSync(ktx2Bytes);
  }
}

Uint8List buildKtx2({
  required int width,
  required int height,
  required int vkFormat,
  required int fillByte,
}) {
  // 12-byte KTX2 identifier
  final id = [0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A];

  // 128x128 in 4x4 blocks = 32x32 blocks = 1024 blocks * 16 bytes = 16384 bytes
  final blocksX = (width + 3) ~/ 4;
  final blocksY = (height + 3) ~/ 4;
  final payloadSize = blocksX * blocksY * 16;
  final payload = Uint8List(payloadSize);
  for (var i = 0; i < payload.length; i++) {
    payload[i] = fillByte;
  }

  const headerSize = 80;
  const levelIndexSize = 24; // 1 level * 24 bytes
  final offset = headerSize + levelIndexSize;
  final totalSize = offset + payloadSize;

  final byteData = ByteData(totalSize);
  var p = 0;

  // 1. Identifier (12 bytes)
  for (final b in id) {
    byteData.setUint8(p++, b);
  }

  // 2. Header fields (little endian)
  byteData.setUint32(p, vkFormat, Endian.little); p += 4;
  byteData.setUint32(p, 1, Endian.little); p += 4; // typeSize
  byteData.setUint32(p, width, Endian.little); p += 4;
  byteData.setUint32(p, height, Endian.little); p += 4;
  byteData.setUint32(p, 0, Endian.little); p += 4; // pixelDepth
  byteData.setUint32(p, 0, Endian.little); p += 4; // layerCount
  byteData.setUint32(p, 1, Endian.little); p += 4; // faceCount
  byteData.setUint32(p, 1, Endian.little); p += 4; // levelCount
  byteData.setUint32(p, 0, Endian.little); p += 4; // supercompressionScheme = 0

  // Index offsets
  byteData.setUint32(p, 0, Endian.little); p += 4; // dfdByteOffset
  byteData.setUint32(p, 0, Endian.little); p += 4; // dfdByteLength
  byteData.setUint32(p, 0, Endian.little); p += 4; // kvdByteOffset
  byteData.setUint32(p, 0, Endian.little); p += 4; // kvdByteLength
  byteData.setUint64(p, 0, Endian.little); p += 8; // sgdByteOffset
  byteData.setUint64(p, 0, Endian.little); p += 8; // sgdByteLength

  // Level index
  byteData.setUint64(p, offset, Endian.little); p += 8; // byteOffset
  byteData.setUint64(p, payloadSize, Endian.little); p += 8; // byteLength
  byteData.setUint64(p, payloadSize, Endian.little); p += 8; // uncompressedByteLength

  // Payload
  final result = byteData.buffer.asUint8List();
  result.setRange(offset, totalSize, payload);

  return result;
}
