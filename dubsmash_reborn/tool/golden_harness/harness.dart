// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// M3-T7: Golden scene set v1 and evaluation harness.
// Computes IoU (Intersection over Union) and flicker score for person mask quality.
//
// Run with: dart tool/golden_harness/harness.dart
// Outputs results to stdout and tool/golden_harness/results.json

import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';

// ── Data types ────────────────────────────────────────────────────────────────

/// A golden scene entry: a pair of (input image, ground-truth binary mask).
class GoldenScene {
  const GoldenScene({
    required this.name,
    required this.inputPath,
    required this.gtMaskPath,
    required this.description,
  });

  final String name;
  final String inputPath; // PNG: raw camera frame (256x256 RGB)
  final String gtMaskPath; // PNG: binary mask (256x256 greyscale, 0=bg, 255=person)
  final String description;
}

/// IoU result for one scene.
class IouResult {
  const IouResult({
    required this.scene,
    required this.iou,
    required this.precision,
    required this.recall,
  });

  final GoldenScene scene;
  final double iou;
  final double precision;
  final double recall;

  Map<String, dynamic> toJson() => {
        'scene': scene.name,
        'iou': iou,
        'precision': precision,
        'recall': recall,
      };
}

/// Flicker score for a sequence of masks (measures temporal instability).
class FlickerResult {
  const FlickerResult({
    required this.clipName,
    required this.flickerScore,
    required this.frameCount,
    required this.threshold,
    required this.passed,
  });

  final String clipName;
  final double flickerScore;
  final int frameCount;
  final double threshold;
  final bool passed;

  Map<String, dynamic> toJson() => {
        'clip': clipName,
        'flicker_score': flickerScore,
        'frame_count': frameCount,
        'threshold': threshold,
        'passed': passed,
      };
}

// ── Golden scene catalog ───────────────────────────────────────────────────────

/// V1 golden scene catalog. These paths are relative to the repo root.
/// Images must be added to tool/golden_harness/scenes/ before running.
const List<GoldenScene> kGoldenScenes = [
  GoldenScene(
    name: 'scene_01_indoor_bright',
    inputPath: 'tool/golden_harness/scenes/scene_01_indoor_bright_input.png',
    gtMaskPath: 'tool/golden_harness/scenes/scene_01_indoor_bright_gt.png',
    description: 'Person in brightly lit indoor room, neutral background',
  ),
  GoldenScene(
    name: 'scene_02_indoor_low_light',
    inputPath: 'tool/golden_harness/scenes/scene_02_indoor_low_light_input.png',
    gtMaskPath: 'tool/golden_harness/scenes/scene_02_indoor_low_light_gt.png',
    description: 'Person in dimly lit room, challenging for segmentation',
  ),
  GoldenScene(
    name: 'scene_03_hair_detail',
    inputPath: 'tool/golden_harness/scenes/scene_03_hair_detail_input.png',
    gtMaskPath: 'tool/golden_harness/scenes/scene_03_hair_detail_gt.png',
    description: 'Person with curly/detailed hair — edge quality test',
  ),
  GoldenScene(
    name: 'scene_04_glasses',
    inputPath: 'tool/golden_harness/scenes/scene_04_glasses_input.png',
    gtMaskPath: 'tool/golden_harness/scenes/scene_04_glasses_gt.png',
    description: 'Person wearing glasses — halo artifact test',
  ),
  GoldenScene(
    name: 'scene_05_hand_raised',
    inputPath: 'tool/golden_harness/scenes/scene_05_hand_raised_input.png',
    gtMaskPath: 'tool/golden_harness/scenes/scene_05_hand_raised_gt.png',
    description: 'Person with hand raised — extremity mask accuracy',
  ),
];

// ── Metric computations ───────────────────────────────────────────────────────

/// Compute IoU between predicted mask and ground-truth mask.
/// Both masks are lists of float values in [0, 1] (length = width * height).
/// Threshold: pixel >= 0.5 is foreground.
IouResult computeIou(GoldenScene scene, List<double> predicted, List<double> gt) {
  assert(predicted.length == gt.length, 'Mask length mismatch');
  int tp = 0, fp = 0, fn = 0;
  for (int i = 0; i < predicted.length; i++) {
    final predFg = predicted[i] >= 0.5;
    final gtFg = gt[i] >= 0.5;
    if (predFg && gtFg) tp++;
    else if (predFg && !gtFg) fp++;
    else if (!predFg && gtFg) fn++;
  }
  final iou = (tp + fp + fn) == 0 ? 1.0 : tp / (tp + fp + fn);
  final precision = (tp + fp) == 0 ? 1.0 : tp / (tp + fp);
  final recall = (tp + fn) == 0 ? 1.0 : tp / (tp + fn);
  return IouResult(scene: scene, iou: iou, precision: precision, recall: recall);
}

/// Compute flicker score from a sequence of mask arrays.
/// Flicker score = mean absolute frame-to-frame pixel difference.
/// Lower is better. Score of 0 = perfectly stable.
FlickerResult computeFlickerScore({
  required String clipName,
  required List<List<double>> maskSequence,
  required double threshold,
}) {
  if (maskSequence.length < 2) {
    return FlickerResult(
      clipName: clipName,
      flickerScore: 0.0,
      frameCount: maskSequence.length,
      threshold: threshold,
      passed: true,
    );
  }
  double totalDiff = 0;
  int count = 0;
  for (int f = 1; f < maskSequence.length; f++) {
    final prev = maskSequence[f - 1];
    final curr = maskSequence[f];
    for (int i = 0; i < math.min(prev.length, curr.length); i++) {
      totalDiff += (curr[i] - prev[i]).abs();
      count++;
    }
  }
  final score = count == 0 ? 0.0 : totalDiff / count;
  return FlickerResult(
    clipName: clipName,
    flickerScore: score,
    frameCount: maskSequence.length,
    threshold: threshold,
    passed: score <= threshold,
  );
}

// ── PNG greyscale reader ──────────────────────────────────────────────────────

/// Minimal PNG greyscale/RGBA reader using dart:io.
/// Returns float list (0..1) of pixel values for the luminance channel.
/// Real usage: use a proper image package. This is a stub for the harness
/// that works with raw float dump files (.bin) produced by the device harness.
List<double> loadMaskFromBin(String path) {
  final file = File(path);
  if (!file.existsSync()) {
    stderr.writeln('WARNING: mask file not found: $path');
    return [];
  }
  // Binary float32 dump: each pixel is a 4-byte little-endian float
  final bytes = file.readAsBytesSync();
  final result = <double>[];
  final byteData = bytes.buffer.asByteData();
  for (int i = 0; i + 3 < bytes.length; i += 4) {
    result.add(byteData.getFloat32(i, Endian.little).clamp(0.0, 1.0));
  }
  return result;
}

// ── Baseline storage ──────────────────────────────────────────────────────────

const _baselineFile = 'tool/golden_harness/baseline.json';

Map<String, double> loadBaseline() {
  final f = File(_baselineFile);
  if (!f.existsSync()) return {};
  final json = jsonDecode(f.readAsStringSync()) as Map<String, dynamic>;
  return json.map((k, v) => MapEntry(k, (v as num).toDouble()));
}

void saveBaseline(Map<String, double> baseline) {
  File(_baselineFile).writeAsStringSync(
    const JsonEncoder.withIndent('  ').convert(baseline),
  );
}

// ── Main harness ─────────────────────────────────────────────────────────────

void main(List<String> args) {
  final isRecord = args.contains('--record');
  print('DSR M3 Golden Harness v1');
  print('Mode: ${isRecord ? 'RECORD baseline' : 'VERIFY against baseline'}');
  print('─' * 60);

  final baseline = loadBaseline();
  final results = <Map<String, dynamic>>[];
  bool allPassed = true;

  // ── IoU evaluation ────────────────────────────────────────────────────────
  print('\n[IoU Evaluation]');
  for (final scene in kGoldenScenes) {
    final predPath = scene.inputPath.replaceAll('_input.png', '_pred.bin');
    final gtPath = scene.gtMaskPath.replaceAll('.png', '.bin');

    final pred = loadMaskFromBin(predPath);
    final gt = loadMaskFromBin(gtPath);

    if (pred.isEmpty || gt.isEmpty) {
      print('  SKIP ${scene.name} (no mask data found — run on-device first)');
      continue;
    }

    final iouResult = computeIou(scene, pred, gt);
    final baselineKey = 'iou_${scene.name}';

    if (isRecord) {
      baseline[baselineKey] = iouResult.iou;
      print('  RECORD ${scene.name}: IoU=${iouResult.iou.toStringAsFixed(4)}');
    } else {
      final baselineIou = baseline[baselineKey];
      if (baselineIou == null) {
        print('  WARN  ${scene.name}: no baseline yet (run --record first)');
      } else {
        final gate = baselineIou - 0.02; // M3-A3: must stay >= baseline - 2%
        final passed = iouResult.iou >= gate;
        if (!passed) allPassed = false;
        print(
          '  ${passed ? "PASS" : "FAIL"} ${scene.name}: '
          'IoU=${iouResult.iou.toStringAsFixed(4)} '
          '(baseline=${baselineIou.toStringAsFixed(4)}, gate=${gate.toStringAsFixed(4)})',
        );
      }
    }
    results.add(iouResult.toJson());
  }

  // ── Flicker evaluation ────────────────────────────────────────────────────
  print('\n[Flicker Evaluation]');
  final flickerClipDir = Directory('tool/golden_harness/clips');
  if (flickerClipDir.existsSync()) {
    final clips = flickerClipDir
        .listSync()
        .whereType<Directory>()
        .toList();
    for (final clipDir in clips) {
      final maskFiles = clipDir
          .listSync()
          .whereType<File>()
          .where((f) => f.path.endsWith('_pred.bin'))
          .toList()
        ..sort((a, b) => a.path.compareTo(b.path));

      final sequence = maskFiles.map((f) => loadMaskFromBin(f.path)).toList();
      final clipName = clipDir.path.split(Platform.pathSeparator).last;
      const defaultThreshold = 0.08; // 8% mean pixel change per frame
      final baselineKey = 'flicker_$clipName';

      final result = computeFlickerScore(
        clipName: clipName,
        maskSequence: sequence,
        threshold: isRecord ? 1.0 : (baseline[baselineKey] ?? defaultThreshold),
      );

      if (isRecord) {
        baseline[baselineKey] = result.flickerScore;
        print('  RECORD $clipName: flicker=${result.flickerScore.toStringAsFixed(4)}');
      } else {
        if (!result.passed) allPassed = false;
        print(
          '  ${result.passed ? "PASS" : "FAIL"} $clipName: '
          'flicker=${result.flickerScore.toStringAsFixed(4)} '
          '(threshold=${result.threshold.toStringAsFixed(4)})',
        );
      }
      results.add(result.toJson());
    }
  } else {
    print('  SKIP flicker (no clips dir found — run on-device capture first)');
  }

  // ── Save baseline / results ───────────────────────────────────────────────
  if (isRecord) {
    saveBaseline(baseline);
    print('\nBaseline saved to $_baselineFile');
  }

  final outputFile = File('tool/golden_harness/results.json');
  outputFile.writeAsStringSync(
    const JsonEncoder.withIndent('  ').convert({
      'timestamp': DateTime.now().toIso8601String(),
      'mode': isRecord ? 'record' : 'verify',
      'all_passed': allPassed,
      'results': results,
    }),
  );

  print('\n─' * 60);
  print('Results written to tool/golden_harness/results.json');
  print('Overall: ${allPassed ? "✅ ALL PASS" : "❌ FAILURES DETECTED"}');
  exitCode = allPassed ? 0 : 1;
}
