// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// RecordScreen — the main preview screen.
//
// At M0 this is a skeleton that:
//   - Calls createSession on the native engine
//   - Renders the resulting textureId via a Flutter Texture widget
//   - Shows the debug HUD (fps, GL ms, tier) from onStats
//
// The screen is deliberately thin — no business logic here.
// State lives in RecordNotifier (domain layer), called via Riverpod.

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../engine/engine_api.dart';
import 'record_controller.dart';

/// Main preview / record screen.
class RecordScreen extends ConsumerStatefulWidget {
  const RecordScreen({super.key});

  @override
  ConsumerState<RecordScreen> createState() => _RecordScreenState();
}

class _RecordScreenState extends ConsumerState<RecordScreen>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // Start the session after the first frame so the Texture widget is mounted.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(recordControllerProvider.notifier).startSession();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    ref.read(recordControllerProvider.notifier).disposeSession();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final notifier = ref.read(recordControllerProvider.notifier);
    if (state == AppLifecycleState.paused) {
      notifier.stopPreview();
    } else if (state == AppLifecycleState.resumed) {
      notifier.resumePreview();
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(recordControllerProvider);

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          // ── Camera preview ─────────────────────────────────────────────
          if (state.textureId != null)
            AspectRatio(
              aspectRatio: state.previewAspectRatio,
              child: Texture(textureId: state.textureId!),
            )
          else
            const Center(child: CircularProgressIndicator()),

          // ── Debug HUD ──────────────────────────────────────────────────
          // Wrapped in RepaintBoundary so HUD updates don't repaint the
          // Texture widget. Use const / small widgets over the preview.
          const Positioned(
            top: 48,
            left: 16,
            child: _DebugHud(),
          ),

          // ── Error banner ───────────────────────────────────────────────
          if (state.error != null)
            Positioned(
              bottom: 80,
              left: 16,
              right: 16,
              child: _ErrorBanner(error: state.error!),
            ),
        ],
      ),
    );
  }
}

// ── Debug HUD ─────────────────────────────────────────────────────────────────

class _DebugHud extends ConsumerWidget {
  const _DebugHud();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stats = ref.watch(frameStatsProvider);
    if (stats == null) return const SizedBox.shrink();

    const flavor =
        String.fromEnvironment('APP_FLAVOR', defaultValue: 'dev');
    if (flavor == 'prod') return const SizedBox.shrink();

    return RepaintBoundary(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color: Colors.black54,
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text(
          '${stats.fpsCurrent.toStringAsFixed(1)} fps  '
          'GL ${stats.glRenderMs.toStringAsFixed(1)} ms  '
          'ML ${stats.mlMs.toStringAsFixed(1)} ms  '
          'Tier ${stats.tier.name.toUpperCase()}',
          style: const TextStyle(
            color: Colors.white,
            fontSize: 11,
            fontFamily: 'monospace',
          ),
        ),
      ),
    );
  }
}

// ── Error Banner ──────────────────────────────────────────────────────────────

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.error});

  final EngineErrorCode error;

  String get _message => switch (error) {
        EngineErrorCode.permission => 'Camera permission required.',
        EngineErrorCode.cameraBusy => 'Camera is in use by another app.',
        EngineErrorCode.surfaceLost => 'Preview surface lost — restarting…',
        EngineErrorCode.modelLoadFailed => 'Failed to load AI model.',
        EngineErrorCode.delegateFailed =>
          'ML accelerator error — falling back to CPU.',
        EngineErrorCode.encoderFailed =>
          'Video encoder failed. Try a shorter recording.',
        EngineErrorCode.themeInvalid => 'Theme download corrupted. Retrying…',
        EngineErrorCode.oom => 'Low memory — reducing quality.',
      };

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Colors.red.shade900.withValues(alpha: 0.9),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Text(
          _message,
          style: const TextStyle(color: Colors.white, fontSize: 13),
        ),
      ),
    );
  }
}
