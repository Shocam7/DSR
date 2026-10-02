#!/usr/bin/env dart
// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// Mock Server — development & CI substitute for the real backend.
//
// Serves:
//   GET  /catalog               → list of tracks
//   GET  /catalog/:id           → track detail + universe pack metadata
//   POST /theme                 → starts an SSE stream that stages theme delivery
//   GET  /health                → {status: "ok"}
//
// Usage:
//   dart run tool/mock_server/server.dart [--port 8080]
//
// The Flutter app in `dev` flavor points to http://10.0.2.2:8080 on emulator
// or http://localhost:8080 on desktop/CI.

// ignore_for_file: avoid_print

import 'dart:async';
import 'dart:convert';
import 'dart:io';

void main(List<String> args) async {
  final port = args.contains('--port')
      ? int.parse(args[args.indexOf('--port') + 1])
      : 8080;

  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, port);
  print('[mock_server] Listening on http://localhost:$port');

  await for (final request in server) {
    unawaited(_handle(request));
  }
}

Future<void> _handle(HttpRequest req) async {
  final path = req.uri.path;
  print('[mock_server] ${req.method} $path');

  req.response.headers.add('Access-Control-Allow-Origin', '*');

  try {
    if (path == '/health') {
      _json(req.response, {'status': 'ok'});
    } else if (path == '/catalog' && req.method == 'GET') {
      _json(req.response, {'tracks': _catalog});
    } else if (path.startsWith('/catalog/') && req.method == 'GET') {
      final id = path.substring('/catalog/'.length);
      final track = _catalog.firstWhere(
        (t) => t['id'] == id,
        orElse: () => <String, Object?>{},
      );
      if (track.isEmpty) {
        req.response.statusCode = 404;
        _json(req.response, {'error': 'not found'});
      } else {
        _json(req.response, track);
      }
    } else if (path == '/theme' && req.method == 'POST') {
      await _handleTheme(req);
    } else {
      req.response.statusCode = 404;
      _json(req.response, {'error': 'unknown path'});
    }
  } catch (e) {
    req.response.statusCode = 500;
    _json(req.response, {'error': e.toString()});
  }
}

void _json(HttpResponse res, Object body) {
  res.headers.contentType = ContentType.json;
  res.write(jsonEncode(body));
  res.close();
}

/// Streams theme stages over SSE (Server-Sent Events).
/// Stages: spec → textures → props (matching the Blueprint §8 timeline).
Future<void> _handleTheme(HttpRequest req) async {
  final body = jsonDecode(await utf8.decodeStream(req)) as Map<String, dynamic>;
  final trackId = body['track_id'] as String? ?? 'trk_0';
  final seed = body['seed'] as int? ?? 0;

  req.response.headers.set('Content-Type', 'text/event-stream');
  req.response.headers.set('Cache-Control', 'no-cache');
  req.response.headers.set('Connection', 'keep-alive');

  void sendEvent(String event, Object data) {
    req.response.write('event: $event\ndata: ${jsonEncode(data)}\n\n');
  }

  // Stage 1: spec + pooled textures (~1.5 s after upload in real backend)
  await Future<void>.delayed(const Duration(milliseconds: 500));
  sendEvent('spec', _buildThemePackage(trackId, seed, ['base']));

  // Stage 2: generated textures
  await Future<void>.delayed(const Duration(milliseconds: 1200));
  sendEvent('textures', _buildThemePackage(trackId, seed, ['base', 'textures']));

  // Stage 3: props
  await Future<void>.delayed(const Duration(milliseconds: 2000));
  sendEvent('props', _buildThemePackage(trackId, seed, ['base', 'textures', 'props']));

  sendEvent('done', {'theme_id': 'th_mock_${seed}_$trackId'});
  await req.response.close();
}

Map<String, Object?> _buildThemePackage(
  String trackId,
  int seed,
  List<String> stages,
) {
  return {
    'schema': 1,
    'theme_id': 'th_mock_${seed}_$trackId',
    'universe': 'uni_inspired_medieval',
    'variant': 'var_great_hall',
    'mode': 'inspired',
    'surfaces': {
      'wall': {'albedo': 'wall_a_mock.ktx2', 'tile_m': 1.2},
      'floor': {'albedo': 'floor_a_mock.ktx2', 'tile_m': 0.8},
    },
    'props': [
      {'match': 'bottle', 'asset': 'goblet_01.glb', 'scale': 1.0},
    ],
    'atmosphere': {
      'lut': 'grade_warm.cube',
      'fog': 0.15,
      'particles': 'embers_low',
    },
    'audio': {'ambience': 'hall_wind.ogg', 'gain_db': -24},
    'stages': stages,
    'expires': '2027-01-01T00:00:00Z',
  };
}

/// Canned catalog — 3 tracks across 2 inspired universes.
final List<Map<String, Object?>> _catalog = [
  {
    'id': 'trk_0001',
    'title': 'Hall of Shadows',
    'artist': 'DSR Originals',
    'duration_s': 180,
    'universe': 'uni_inspired_medieval',
    'territory': ['IN', 'US', 'GB'],
    'expires': '2027-01-01T00:00:00Z',
    'mode': 'inspired',
    'cue_track': {
      'bpm': 120,
      'downbeats': [0.0, 2.0, 4.0],
      'cues': [{'t': 32.0, 'type': 'scene_change'}],
    },
  },
  {
    'id': 'trk_0002',
    'title': 'Galaxy Drift',
    'artist': 'DSR Originals',
    'duration_s': 150,
    'universe': 'uni_inspired_scifi',
    'territory': ['IN', 'US', 'GB'],
    'expires': '2027-01-01T00:00:00Z',
    'mode': 'inspired',
    'cue_track': {
      'bpm': 140,
      'downbeats': [0.0, 1.71, 3.43],
      'cues': [{'t': 20.0, 'type': 'scene_change'}],
    },
  },
  {
    'id': 'trk_0003',
    'title': 'Ember Crown',
    'artist': 'DSR Originals',
    'duration_s': 200,
    'universe': 'uni_inspired_medieval',
    'territory': ['IN'],
    // Intentionally expired to test territory/expiry enforcement.
    'expires': '2020-01-01T00:00:00Z',
    'mode': 'inspired',
    'cue_track': {
      'bpm': 90,
      'downbeats': <double>[],
      'cues': <Map<String, Object>>[],
    },
  },
];
