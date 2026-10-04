// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// LibraryScreen — displays saved recordings from Drift database (M2-T5).

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/database/app_database.dart';
import '../../../core/database/database_provider.dart';
import '../../record/presentation/trim_screen.dart';

class LibraryScreen extends ConsumerWidget {
  const LibraryScreen({super.key});

  String _formatDuration(int durationMs) {
    final seconds = (durationMs / 1000).round();
    final mins = seconds ~/ 60;
    final remSecs = seconds % 60;
    return '${mins.toString().padLeft(2, '0')}:${remSecs.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final db = ref.watch(databaseProvider);

    return Scaffold(
      backgroundColor: const Color(0xFF121212),
      appBar: AppBar(
        title: const Text('My Recordings'),
        backgroundColor: Colors.black54,
        elevation: 0,
      ),
      body: StreamBuilder<List<Recording>>(
        stream: db.watchAllRecordings(),
        builder: (context, snapshot) {
          if (!snapshot.hasData) {
            return const Center(child: CircularProgressIndicator(color: Colors.white70));
          }

          final recordings = snapshot.data!;
          if (recordings.isEmpty) {
            return const Center(
              child: Padding(
                padding: EdgeInsets.all(32.0),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.video_library_rounded, size: 72, color: Colors.white24),
                    SizedBox(height: 16),
                    Text(
                      'No Recordings Yet',
                      style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold),
                    ),
                    SizedBox(height: 8),
                    Text(
                      'Hit the record button to capture your first performance in your cinematic universe!',
                      style: TextStyle(color: Colors.white54, fontSize: 13, height: 1.4),
                      textAlign: TextAlign.center,
                    ),
                  ],
                ),
              ),
            );
          }

          return ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: recordings.length,
            separatorBuilder: (_, __) => const SizedBox(height: 12),
            itemBuilder: (context, index) {
              final rec = recordings[index];
              final hasTrim = rec.trimEndMs > 0 &&
                  (rec.trimStartMs > 0 || rec.trimEndMs < rec.durationMs);

              return Container(
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.05),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.white12),
                ),
                child: ListTile(
                  contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  leading: Container(
                    width: 48,
                    height: 48,
                    decoration: BoxDecoration(
                      color: Colors.deepPurple.shade900.withValues(alpha: 0.5),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: const Icon(Icons.movie_rounded, color: Colors.purpleAccent),
                  ),
                  title: Text(
                    rec.filePath.split('/').last,
                    style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600, fontSize: 14),
                  ),
                  subtitle: Padding(
                    padding: const EdgeInsets.only(top: 4.0),
                    child: Row(
                      children: [
                        Text(
                          _formatDuration(rec.durationMs),
                          style: const TextStyle(color: Colors.white70, fontSize: 12),
                        ),
                        if (hasTrim) ...[
                          const SizedBox(width: 8),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                            decoration: BoxDecoration(
                              color: Colors.deepPurpleAccent.withValues(alpha: 0.3),
                              borderRadius: BorderRadius.circular(4),
                            ),
                            child: const Text(
                              'Trimmed',
                              style: TextStyle(color: Colors.deepPurpleAccent, fontSize: 10, fontWeight: FontWeight.bold),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        icon: Icon(
                          rec.isFavorite ? Icons.favorite_rounded : Icons.favorite_border_rounded,
                          color: rec.isFavorite ? Colors.redAccent : Colors.white54,
                          size: 20,
                        ),
                        onPressed: () => db.toggleFavorite(rec.id, !rec.isFavorite),
                      ),
                      IconButton(
                        icon: const Icon(Icons.content_cut_rounded, color: Colors.white70, size: 20),
                        tooltip: 'Trim',
                        onPressed: () {
                          Navigator.of(context).push(
                            MaterialPageRoute<void>(
                              builder: (_) => TrimScreen(
                                recordingId: rec.id,
                                filePath: rec.filePath,
                                durationMs: rec.durationMs,
                              ),
                            ),
                          );
                        },
                      ),
                      IconButton(
                        icon: const Icon(Icons.delete_outline_rounded, color: Colors.redAccent, size: 20),
                        tooltip: 'Delete',
                        onPressed: () => db.deleteRecording(rec.id),
                      ),
                    ],
                  ),
                ),
              );
            },
          );
        },
      ),
    );
  }
}
