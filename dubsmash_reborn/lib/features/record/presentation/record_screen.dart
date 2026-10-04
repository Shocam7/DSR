// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// RecordScreen — main preview and recording screen.
//
// M1: Camera/GL preview, permissions, aspect ratio, pause/resume, camera switch, debug HUD
// M2: Countdown, recording timer, progress bar, MediaCodec recording, Trim/Library, A/V calibration
// M3: Background effect button (segmentation on/off, color/blur), BackgroundEffectPanel bottom sheet

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:permission_handler/permission_handler.dart';

import '../../../engine/engine_api.dart';
import '../../library/presentation/library_screen.dart';
import 'background_effect_panel.dart';
import 'calibration_screen.dart';
import 'record_controller.dart';
import 'trim_screen.dart';

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
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(recordControllerProvider.notifier).checkAndRequestPermissions();
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
    final currentState = ref.read(recordControllerProvider);

    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.inactive) {
      notifier.onAppPaused();
    } else if (state == AppLifecycleState.resumed) {
      if (!currentState.isPermissionsGranted) {
        notifier.checkAndRequestPermissions();
      } else {
        notifier.onAppResumed();
      }
    }
  }

  String _formatElapsed(int seconds) {
    final mins = seconds ~/ 60;
    final remSecs = seconds % 60;
    return '${mins.toString().padLeft(2, '0')}:${remSecs.toString().padLeft(2, '0')}';
  }

  void _showAudioOptions(
    BuildContext context,
    RecordController notifier,
    RecordState state,
  ) {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.grey.shade900,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (bottomSheetContext) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 8),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 40,
                  height: 4,
                  decoration: BoxDecoration(
                    color: Colors.white24,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
                const SizedBox(height: 16),
                const Text(
                  'Background Audio',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 16),
                ListTile(
                  leading: const CircleAvatar(
                    backgroundColor: Colors.deepPurpleAccent,
                    child: Icon(Icons.music_note_rounded, color: Colors.white),
                  ),
                  title: const Text(
                    'Dub Groove (Electro / Dub Beat)',
                    style: TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  subtitle: const Text(
                    'Catchy 124 BPM rhythm with punchy drums and synth bass',
                    style: TextStyle(color: Colors.white60, fontSize: 12),
                  ),
                  onTap: () {
                    Navigator.of(bottomSheetContext).pop();
                    notifier.selectPresetAudioTrack(
                      'assets/audio/dub_groove.wav',
                      'Dub Groove (124 BPM)',
                    );
                  },
                ),
                ListTile(
                  leading: const CircleAvatar(
                    backgroundColor: Colors.indigoAccent,
                    child: Icon(Icons.electric_bolt_rounded, color: Colors.white),
                  ),
                  title: const Text(
                    'Energetic Beat (Upbeat Pop)',
                    style: TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  subtitle: const Text(
                    'High-energy 135 BPM dance beat for dubbing',
                    style: TextStyle(color: Colors.white60, fontSize: 12),
                  ),
                  onTap: () {
                    Navigator.of(bottomSheetContext).pop();
                    notifier.selectPresetAudioTrack(
                      'assets/audio/energetic_beat.wav',
                      'Energetic Beat (135 BPM)',
                    );
                  },
                ),
                ListTile(
                  leading: const CircleAvatar(
                    backgroundColor: Colors.pinkAccent,
                    child: Icon(Icons.folder_open_rounded, color: Colors.white),
                  ),
                  title: const Text(
                    'Choose from Device Files',
                    style: TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  subtitle: const Text(
                    'Select an MP3, AAC, WAV, or M4A audio file',
                    style: TextStyle(color: Colors.white60, fontSize: 12),
                  ),
                  onTap: () {
                    Navigator.of(bottomSheetContext).pop();
                    notifier.selectAudioFile();
                  },
                ),
                if (state.selectedAudioPath != null) ...[
                  const Divider(color: Colors.white24),
                  ListTile(
                    leading: const CircleAvatar(
                      backgroundColor: Colors.redAccent,
                      child: Icon(Icons.music_off_rounded, color: Colors.white),
                    ),
                    title: const Text(
                      'Remove Current Sound',
                      style: TextStyle(
                        color: Colors.redAccent,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    subtitle: Text(
                      state.selectedAudioTitle ?? '',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: Colors.white60,
                        fontSize: 12,
                      ),
                    ),
                    onTap: () {
                      Navigator.of(bottomSheetContext).pop();
                      notifier.clearSelectedAudio();
                    },
                  ),
                ],
              ],
            ),
          ),
        );
      },
    );
  }

  void _showThemeOptions(
    BuildContext context,
    RecordController notifier,
    RecordState state,
  ) {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.grey.shade900,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (bottomSheetContext) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 8),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 40,
                  height: 4,
                  decoration: BoxDecoration(
                    color: Colors.white24,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
                const SizedBox(height: 16),
                const Text(
                  'Room Theme',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 16),
                ListTile(
                  leading: const CircleAvatar(
                    backgroundColor: Colors.blueGrey,
                    child: Icon(Icons.castle_rounded, color: Colors.white),
                  ),
                  title: const Text(
                    'Medieval Stone (Theme 1)',
                    style: TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  subtitle: const Text(
                    'Weathered dungeon walls and cobblestone flooring',
                    style: TextStyle(color: Colors.white60, fontSize: 12),
                  ),
                  onTap: () {
                    Navigator.of(bottomSheetContext).pop();
                    notifier.applyThemeBundle(
                      themeAssetDir: 'assets/themes/medieval_stone',
                    );
                  },
                ),
                ListTile(
                  leading: const CircleAvatar(
                    backgroundColor: Colors.purpleAccent,
                    child: Icon(Icons.lightbulb_rounded, color: Colors.white),
                  ),
                  title: const Text(
                    'Cyberpunk Neon (Theme 2)',
                    style: TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  subtitle: const Text(
                    'Dark carbon panels and glowing neon floor grid',
                    style: TextStyle(color: Colors.white60, fontSize: 12),
                  ),
                  onTap: () {
                    Navigator.of(bottomSheetContext).pop();
                    notifier.applyThemeBundle(
                      themeAssetDir: 'assets/themes/cyberpunk_neon',
                    );
                  },
                ),
                ListTile(
                  leading: const CircleAvatar(
                    backgroundColor: Colors.teal,
                    child: Icon(Icons.cleaning_services_rounded, color: Colors.white),
                  ),
                  title: const Text(
                    'Base Look (Neutral)',
                    style: TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  subtitle: const Text(
                    'Clean neutral walls and floors',
                    style: TextStyle(color: Colors.white60, fontSize: 12),
                  ),
                  onTap: () {
                    Navigator.of(bottomSheetContext).pop();
                    notifier.applyThemeBundle(
                      themeAssetDir: 'assets/themes/base_look',
                    );
                  },
                ),
                if (state.activeThemeId != null) ...[
                  const Divider(color: Colors.white24),
                  ListTile(
                    leading: const CircleAvatar(
                      backgroundColor: Colors.redAccent,
                      child: Icon(Icons.close_rounded, color: Colors.white),
                    ),
                    title: const Text(
                      'Clear Active Theme',
                      style: TextStyle(
                        color: Colors.redAccent,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    onTap: () {
                      Navigator.of(bottomSheetContext).pop();
                      notifier.clearTheme();
                    },
                  ),
                ],
              ],
            ),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(recordControllerProvider);
    final notifier = ref.read(recordControllerProvider.notifier);

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          // ── Camera preview ─────────────────────────────────────────────
          if (state.textureId != null)
            Center(
              child: ClipRect(
                child: FittedBox(
                  fit: state.isCoverFit ? BoxFit.cover : BoxFit.contain,
                  child: SizedBox(
                    width: state.previewWidth.toDouble(),
                    height: state.previewHeight.toDouble(),
                    child: Texture(textureId: state.textureId!),
                  ),
                ),
              ),
            )
          else if (!state.isPermissionsGranted &&
              state.error == EngineErrorCode.permission)
            _PermissionRequestView(
              isPermanentlyDenied: state.isPermanentlyDenied,
              onRequestPermission: () {
                if (state.isPermanentlyDenied) {
                  openAppSettings();
                } else {
                  notifier.checkAndRequestPermissions();
                }
              },
            )
          else
            const Center(
              child: CircularProgressIndicator(color: Colors.white70),
            ),

          // ── Top: Recording Progress Bar & Timer ─────────────────────────
          if (state.isRecording)
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: SafeArea(
                child: Column(
                  children: [
                    LinearProgressIndicator(
                      value: state.recordingElapsedSeconds /
                          RecordController.maxRecordingSeconds,
                      backgroundColor: Colors.white24,
                      valueColor: const AlwaysStoppedAnimation<Color>(
                        Colors.redAccent,
                      ),
                      minHeight: 4,
                    ),
                    const SizedBox(height: 12),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 14,
                        vertical: 6,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.black54,
                        borderRadius: BorderRadius.circular(20),
                        border: Border.all(color: Colors.redAccent, width: 1.5),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Container(
                            width: 10,
                            height: 10,
                            decoration: const BoxDecoration(
                              color: Colors.redAccent,
                              shape: BoxShape.circle,
                            ),
                          ),
                          const SizedBox(width: 8),
                          Text(
                            'REC ${_formatElapsed(state.recordingElapsedSeconds)} / 01:00',
                            style: const TextStyle(
                              color: Colors.white,
                              fontWeight: FontWeight.bold,
                              fontSize: 13,
                              fontFamily: 'monospace',
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),

          // ── Audio Route Warning Banner (M2-T6) ──────────────────────────
          if (state.audioRouteWarning != null)
            Positioned(
              top: 52,
              left: 16,
              right: 16,
              child: SafeArea(
                child: Material(
                  color: Colors.transparent,
                  child: Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: Colors.amber.shade900.withValues(alpha: 0.95),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Row(
                      children: [
                        const Icon(
                          Icons.headset_off_rounded,
                          color: Colors.white,
                          size: 22,
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            state.audioRouteWarning!,
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),

          // ── Countdown Overlay (M2-T5) ──────────────────────────────────
          if (state.countdown > 0)
            Positioned.fill(
              child: GestureDetector(
                onTap: notifier.cancelCountdown,
                child: Container(
                  color: Colors.black87,
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          '${state.countdown}',
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 100,
                            fontWeight: FontWeight.w900,
                          ),
                        ),
                        const SizedBox(height: 16),
                        const Text(
                          'Tap anywhere to cancel',
                          style: TextStyle(
                            color: Colors.white60,
                            fontSize: 14,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),

          // ── Paused Screen Overlay ───────────────────────────────────────
          if (state.textureId != null && state.isPaused && !state.isRecording)
            Positioned.fill(
              child: GestureDetector(
                onTap: notifier.togglePause,
                child: Container(
                  color: Colors.black54,
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Container(
                          padding: const EdgeInsets.all(20),
                          decoration: BoxDecoration(
                            color: Colors.white.withValues(alpha: 0.2),
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(
                            Icons.play_arrow_rounded,
                            color: Colors.white,
                            size: 48,
                          ),
                        ),
                        const SizedBox(height: 16),
                        const Text(
                          'Preview Paused',
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 18,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(height: 6),
                        const Text(
                          'Tap anywhere to resume',
                          style: TextStyle(
                            color: Colors.white70,
                            fontSize: 13,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),

          // ── Top Left: Debug HUD ─────────────────────────────────────────
          if (!state.isRecording)
            const Positioned(
              top: 96,
              left: 16,
              child: _DebugHud(),
            ),

          // ── Top Center: Sound Pill (Background Audio) ───────────────────
          if (state.textureId != null && !state.isRecording)
            Positioned(
              top: 48,
              left: 110,
              right: 70,
              child: Center(
                child: GestureDetector(
                  onTap: () => _showAudioOptions(context, notifier, state),
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 6,
                    ),
                    decoration: BoxDecoration(
                      color: state.selectedAudioPath != null
                          ? Colors.pink.shade900.withValues(alpha: 0.85)
                          : Colors.black54,
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(
                        color: state.selectedAudioPath != null
                            ? Colors.pinkAccent
                            : Colors.white24,
                        width: 1.2,
                      ),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.music_note_rounded,
                          size: 16,
                          color: state.selectedAudioPath != null
                              ? Colors.pinkAccent
                              : Colors.white70,
                        ),
                        const SizedBox(width: 6),
                        Flexible(
                          child: Text(
                            state.selectedAudioTitle ?? 'Add Sound',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: Colors.white,
                              fontSize: 12,
                              fontWeight: state.selectedAudioPath != null
                                  ? FontWeight.bold
                                  : FontWeight.normal,
                            ),
                          ),
                        ),
                        if (state.selectedAudioPath != null) ...[
                          const SizedBox(width: 4),
                          GestureDetector(
                            onTap: notifier.clearSelectedAudio,
                            child: const Padding(
                              padding: EdgeInsets.symmetric(horizontal: 2),
                              child: Icon(
                                Icons.close_rounded,
                                size: 16,
                                color: Colors.white70,
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ),

          // ── Right Side Action Controls Column ───────────────────────────
          if (state.textureId != null && !state.isRecording)
            Positioned(
              top: 100,
              right: 16,
              child: RepaintBoundary(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    // Background Audio Track button
                    _CircleActionButton(
                      icon: state.selectedAudioPath != null
                          ? Icons.music_note_rounded
                          : Icons.music_note_outlined,
                      tooltip: state.selectedAudioTitle ?? 'Add Background Sound',
                      color: state.selectedAudioPath != null
                          ? Colors.pinkAccent.shade700
                          : Colors.black45,
                      onPressed: () => _showAudioOptions(context, notifier, state),
                    ),
                    const SizedBox(height: 12),
                    // Flip camera button
                    _CircleActionButton(
                      icon: Icons.cameraswitch_rounded,
                      tooltip: 'Switch Camera',
                      onPressed: notifier.switchCamera,
                    ),
                    const SizedBox(height: 12),
                    // Pause / Resume button
                    _CircleActionButton(
                      icon: state.isPaused
                          ? Icons.play_arrow_rounded
                          : Icons.pause_rounded,
                      tooltip:
                          state.isPaused ? 'Resume preview' : 'Pause preview',
                      color: state.isPaused
                          ? Colors.greenAccent.shade700
                          : Colors.black45,
                      onPressed: notifier.togglePause,
                    ),
                    const SizedBox(height: 12),
                    // Fit mode toggle (Fill screen vs 9:16 fit)
                    _CircleActionButton(
                      icon: state.isCoverFit
                          ? Icons.fit_screen_rounded
                          : Icons.aspect_ratio_rounded,
                      tooltip: state.isCoverFit
                          ? 'Switch to 9:16 fit'
                          : 'Switch to full cover',
                      onPressed: notifier.toggleFitMode,
                    ),
                    const SizedBox(height: 12),
                    // Audio Latency Calibration Button (M2-T4)
                    _CircleActionButton(
                      icon: Icons.graphic_eq_rounded,
                      tooltip: 'Audio Calibration',
                      onPressed: () {
                        Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (_) => const CalibrationScreen(),
                          ),
                        );
                      },
                    ),
                    const SizedBox(height: 12),
                    // Local Recordings Library Button (M2-T5)
                    _CircleActionButton(
                      icon: Icons.video_library_rounded,
                      tooltip: 'My Recordings',
                      onPressed: () {
                        Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (_) => const LibraryScreen(),
                          ),
                        );
                      },
                    ),
                    const SizedBox(height: 12),
                    // M3-T5: Background Effect Button
                    Consumer(
                      builder: (ctx, ref, _) {
                        final bgState = ref.watch(backgroundEffectProvider);
                        return _CircleActionButton(
                          icon: bgState.enabled
                              ? Icons.blur_on_rounded
                              : Icons.blur_off_rounded,
                          tooltip: 'Background Effect',
                          color: bgState.enabled
                              ? Colors.cyanAccent.shade700
                              : Colors.black45,
                          onPressed: () => showModalBottomSheet<void>(
                            context: context,
                            backgroundColor: Colors.transparent,
                            builder: (_) => BackgroundEffectPanel(
                              onChanged: (s) {
                                notifier.applyBackgroundEffect(
                                  enabled: s.enabled,
                                  useBlur: s.useBlur,
                                  colorArgb: s.color.toARGB32(),
                                  blurRadius: s.blurRadius,
                                );
                              },
                            ),
                          ),
                        );
                      },
                    ),
                    const SizedBox(height: 12),
                    // M4-T6: Theme Selector Button
                    _CircleActionButton(
                      icon: state.activeThemeId != null
                          ? Icons.palette_rounded
                          : Icons.palette_outlined,
                      tooltip: state.activeThemeId != null
                          ? 'Theme: ${state.activeThemeId}'
                          : 'Select Room Theme',
                      color: state.activeThemeId != null
                          ? Colors.deepPurpleAccent
                          : Colors.black45,
                      onPressed: () => _showThemeOptions(context, notifier, state),
                    ),
                  ],
                ),
              ),
            ),

          // ── Bottom Controls Bar (Record / Stop Button) ──────────────────
          if (state.textureId != null)
            Positioned(
              bottom: 40,
              left: 0,
              right: 0,
              child: Center(
                child: GestureDetector(
                  onTap: () async {
                    if (state.isRecording) {
                      final result = await notifier.stopRecording();
                      if (result != null && context.mounted) {
                        Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (_) => TrimScreen(
                              recordingId:
                                  'rec_${DateTime.now().millisecondsSinceEpoch}',
                              filePath: result.outputPath,
                              durationMs: result.durationMs,
                            ),
                          ),
                        );
                      }
                    } else {
                      notifier.startCountdownAndRecord();
                    }
                  },
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 200),
                    width: state.isRecording ? 76 : 72,
                    height: state.isRecording ? 76 : 72,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: state.isRecording ? Colors.redAccent : Colors.white,
                        width: 4,
                      ),
                      color: state.isRecording
                          ? Colors.red.withValues(alpha: 0.3)
                          : Colors.red.withValues(alpha: 0.8),
                    ),
                    child: Center(
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 200),
                        width: state.isRecording ? 28 : 56,
                        height: state.isRecording ? 28 : 56,
                        decoration: BoxDecoration(
                          color: state.isPaused
                              ? Colors.grey
                              : Colors.redAccent,
                          borderRadius: BorderRadius.circular(
                            state.isRecording ? 6 : 28,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),

          // ── Error banner ───────────────────────────────────────────────
          if (state.error != null && state.error != EngineErrorCode.permission)
            Positioned(
              bottom: 120,
              left: 16,
              right: 16,
              child: _ErrorBanner(error: state.error!),
            ),
        ],
      ),
    );
  }
}

// ── Circular Action Button ───────────────────────────────────────────────────

class _CircleActionButton extends StatelessWidget {
  const _CircleActionButton({
    required this.icon,
    required this.tooltip,
    required this.onPressed,
    this.color = Colors.black45,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: color,
        shape: BoxShape.circle,
      ),
      child: IconButton(
        icon: Icon(icon, color: Colors.white, size: 24),
        tooltip: tooltip,
        onPressed: onPressed,
      ),
    );
  }
}

// ── Permission Request View ───────────────────────────────────────────────────

class _PermissionRequestView extends StatelessWidget {
  const _PermissionRequestView({
    required this.isPermanentlyDenied,
    required this.onRequestPermission,
  });

  final bool isPermanentlyDenied;
  final VoidCallback onRequestPermission;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.videocam_rounded,
              size: 64,
              color: Colors.white70,
            ),
            const SizedBox(height: 20),
            const Text(
              'Camera & Mic Access',
              style: TextStyle(
                color: Colors.white,
                fontSize: 20,
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 12),
            const Text(
              'DubSmash Reborn needs camera and microphone access to rebuild your room into cinematic sets and capture your performance.',
              style: TextStyle(
                color: Colors.white70,
                fontSize: 14,
                height: 1.4,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 28),
            FilledButton.icon(
              onPressed: onRequestPermission,
              style: FilledButton.styleFrom(
                backgroundColor: Colors.deepPurpleAccent,
                foregroundColor: Colors.white,
                padding:
                    const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
              icon: Icon(
                isPermanentlyDenied
                    ? Icons.settings_rounded
                    : Icons.lock_open_rounded,
                size: 20,
              ),
              label: Text(
                isPermanentlyDenied ? 'Open Settings' : 'Allow Access',
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
            ),
          ],
        ),
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

    const flavor = String.fromEnvironment('APP_FLAVOR', defaultValue: 'dev');
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
