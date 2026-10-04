// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// TrimScreen — in-app video trim, preview playback, and gallery export (M2-T5).

import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:video_player/video_player.dart';

import '../../../core/database/database_provider.dart';
import '../../../engine/engine_providers.dart';

class TrimScreen extends ConsumerStatefulWidget {
  const TrimScreen({
    required this.recordingId,
    required this.filePath,
    required this.durationMs,
    super.key,
  });

  final String recordingId;
  final String filePath;
  final int durationMs;

  @override
  ConsumerState<TrimScreen> createState() => _TrimScreenState();
}

class _TrimScreenState extends ConsumerState<TrimScreen> {
  late RangeValues _currentRangeValues;
  bool _isSaving = false;
  VideoPlayerController? _videoPlayerController;
  bool _isPlayerInitialized = false;
  bool _isPlaying = false;

  @override
  void initState() {
    super.initState();
    _currentRangeValues = RangeValues(
      0.0,
      widget.durationMs.toDouble().clamp(1000.0, double.infinity),
    );
    _initVideoPlayer();
  }

  Future<void> _initVideoPlayer() async {
    try {
      final file = File(widget.filePath);
      if (await file.exists()) {
        final controller = VideoPlayerController.file(file);
        await controller.initialize();
        controller.setLooping(false);
        controller.addListener(_videoPlayerListener);
        if (mounted) {
          setState(() {
            _videoPlayerController = controller;
            _isPlayerInitialized = true;
          });
          controller.play();
        }
      }
    } catch (e) {
      debugPrint('Error initializing video player in TrimScreen: $e');
    }
  }

  void _videoPlayerListener() {
    if (_videoPlayerController == null || !mounted) return;
    final posMs =
        _videoPlayerController!.value.position.inMilliseconds.toDouble();
    final endMs = _currentRangeValues.end;
    final startMs = _currentRangeValues.start;

    // Loop playback within trim range
    if (posMs >= endMs) {
      _videoPlayerController!.seekTo(Duration(milliseconds: startMs.round()));
      if (_videoPlayerController!.value.isPlaying) {
        _videoPlayerController!.play();
      }
    }

    final isPlaying = _videoPlayerController!.value.isPlaying;
    if (isPlaying != _isPlaying) {
      setState(() {
        _isPlaying = isPlaying;
      });
    }
  }

  @override
  void dispose() {
    _videoPlayerController?.removeListener(_videoPlayerListener);
    _videoPlayerController?.dispose();
    super.dispose();
  }

  String _formatMs(double ms) {
    final totalSeconds = (ms / 1000).floor();
    final minutes = (totalSeconds / 60).floor();
    final seconds = totalSeconds % 60;
    final tenths = ((ms % 1000) / 100).floor();
    return '${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}.$tenths';
  }

  Future<void> _saveTrim() async {
    setState(() {
      _isSaving = true;
    });

    final db = ref.read(databaseProvider);
    final startMs = _currentRangeValues.start.round();
    final endMs = _currentRangeValues.end.round();

    await db.updateTrim(widget.recordingId, startMs, endMs);

    // Ensure exported and scanned into device gallery (DCIM/Dubsmash)
    try {
      final engine = ref.read(engineApiProvider);
      await engine.exportToGallery(widget.filePath);
    } catch (e) {
      debugPrint('Gallery export error: $e');
    }

    if (mounted) {
      setState(() {
        _isSaving = false;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Trim saved & added to Gallery (DCIM/Dubsmash)!'),
          backgroundColor: Colors.green,
          duration: Duration(seconds: 3),
        ),
      );
      Navigator.of(context).pop();
    }
  }

  Future<void> _shareRecording() async {
    try {
      final engine = ref.read(engineApiProvider);
      await engine.exportToGallery(widget.filePath);
    } catch (_) {}

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            'Saved to Gallery (DCIM/Dubsmash): ${widget.filePath.split('/').last}',
          ),
          backgroundColor: Colors.deepPurpleAccent,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final maxDuration =
        widget.durationMs.toDouble().clamp(1000.0, double.infinity);

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: const Text('Trim & Review'),
        backgroundColor: Colors.black54,
        elevation: 0,
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Video preview card with actual video playback
              Expanded(
                child: Container(
                  decoration: BoxDecoration(
                    color: Colors.black,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: Colors.white24),
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(16),
                    child: Stack(
                      alignment: Alignment.center,
                      children: [
                        if (_isPlayerInitialized &&
                            _videoPlayerController != null)
                          Center(
                            child: AspectRatio(
                              aspectRatio:
                                  _videoPlayerController!.value.aspectRatio > 0
                                      ? _videoPlayerController!
                                          .value.aspectRatio
                                      : 9 / 16,
                              child: VideoPlayer(_videoPlayerController!),
                            ),
                          )
                        else
                          const Center(
                            child: CircularProgressIndicator(
                              color: Colors.deepPurpleAccent,
                            ),
                          ),

                        // Interactive Play / Pause tap gesture overlay
                        GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onTap: () {
                            if (_videoPlayerController == null) return;
                            if (_videoPlayerController!.value.isPlaying) {
                              _videoPlayerController!.pause();
                            } else {
                              final pos = _videoPlayerController!
                                  .value.position.inMilliseconds;
                              if (pos >= _currentRangeValues.end ||
                                  pos < _currentRangeValues.start) {
                                _videoPlayerController!.seekTo(
                                  Duration(
                                    milliseconds:
                                        _currentRangeValues.start.round(),
                                  ),
                                );
                              }
                              _videoPlayerController!.play();
                            }
                          },
                          child: AnimatedOpacity(
                            opacity: _isPlaying ? 0.0 : 1.0,
                            duration: const Duration(milliseconds: 200),
                            child: Container(
                              padding: const EdgeInsets.all(16),
                              decoration: const BoxDecoration(
                                color: Colors.black54,
                                shape: BoxShape.circle,
                              ),
                              child: const Icon(
                                Icons.play_arrow_rounded,
                                color: Colors.white,
                                size: 54,
                              ),
                            ),
                          ),
                        ),

                        // Top info badge: file name & duration
                        Positioned(
                          top: 12,
                          left: 12,
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 4,
                            ),
                            decoration: BoxDecoration(
                              color: Colors.black54,
                              borderRadius: BorderRadius.circular(6),
                            ),
                            child: Text(
                              widget.filePath.split('/').last,
                              style: const TextStyle(
                                color: Colors.white70,
                                fontSize: 11,
                                fontFamily: 'monospace',
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 20),

              // Trim timeline bar
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: Colors.white10,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Column(
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'In: ${_formatMs(_currentRangeValues.start)}',
                          style: const TextStyle(
                            color: Colors.deepPurpleAccent,
                            fontWeight: FontWeight.bold,
                            fontSize: 14,
                          ),
                        ),
                        Text(
                          'Trimmed: ${_formatMs(_currentRangeValues.end - _currentRangeValues.start)}',
                          style: const TextStyle(
                            color: Colors.white70,
                            fontSize: 13,
                          ),
                        ),
                        Text(
                          'Out: ${_formatMs(_currentRangeValues.end)}',
                          style: const TextStyle(
                            color: Colors.deepPurpleAccent,
                            fontWeight: FontWeight.bold,
                            fontSize: 14,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    RangeSlider(
                      values: _currentRangeValues,
                      min: 0.0,
                      max: maxDuration,
                      divisions: 100,
                      activeColor: Colors.deepPurpleAccent,
                      inactiveColor: Colors.white24,
                      onChanged: (RangeValues values) {
                        setState(() {
                          _currentRangeValues = values;
                        });
                        // Scrub video to matching start position for instant frame visual feedback
                        _videoPlayerController?.seekTo(
                          Duration(milliseconds: values.start.round()),
                        );
                      },
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 20),

              // Action Buttons: Share & Save Trim
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: _shareRecording,
                      style: OutlinedButton.styleFrom(
                        foregroundColor: Colors.white,
                        side: const BorderSide(color: Colors.white24),
                        padding: const EdgeInsets.symmetric(vertical: 14),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(10),
                        ),
                      ),
                      icon: const Icon(Icons.share_rounded),
                      label: const Text('Share'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: _isSaving ? null : _saveTrim,
                      style: FilledButton.styleFrom(
                        backgroundColor: Colors.deepPurpleAccent,
                        padding: const EdgeInsets.symmetric(vertical: 14),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(10),
                        ),
                      ),
                      icon: _isSaving
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: Colors.white,
                              ),
                            )
                          : const Icon(Icons.check_rounded),
                      label: const Text(
                        'Save Trim',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
