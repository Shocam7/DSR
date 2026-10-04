// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// AppDatabase — local Drift database managing recorded clips, trim metadata,
// audio latency offset, and user favorites (M2-T5, M2-A4).

import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

part 'app_database.g.dart';

/// Table representing local recordings.
class Recordings extends Table {
  TextColumn get id => text()();
  TextColumn get filePath => text()();
  IntColumn get durationMs => integer()();
  TextColumn get themeId => text()();
  DateTimeColumn get createdAt => dateTime()();
  TextColumn get thumbnailPath => text().nullable()();
  IntColumn get trimStartMs => integer().withDefault(const Constant(0))();
  IntColumn get trimEndMs => integer().withDefault(const Constant(0))();
  IntColumn get audioOffsetMs => integer().withDefault(const Constant(0))();
  BoolColumn get isFavorite => boolean().withDefault(const Constant(false))();

  @override
  Set<Column> get primaryKey => {id};
}

@DriftDatabase(tables: [Recordings])
class AppDatabase extends _$AppDatabase {
  AppDatabase([QueryExecutor? e]) : super(e ?? _openConnection());

  @override
  int get schemaVersion => 2;

  @override
  MigrationStrategy get migration => MigrationStrategy(
        onCreate: (m) async {
          await m.createAll();
        },
        onUpgrade: (m, from, to) async {
          if (from == 1) {
            // Migration v1 -> v2: added isFavorite column
            await m.addColumn(recordings, recordings.isFavorite);
          }
        },
      );

  // ── Queries ─────────────────────────────────────────────────────────────────

  /// Streams all recordings ordered newest first.
  Stream<List<Recording>> watchAllRecordings() =>
      (select(recordings)..orderBy([(r) => OrderingTerm.desc(r.createdAt)]))
          .watch();

  /// Gets all recordings ordered newest first.
  Future<List<Recording>> getAllRecordings() =>
      (select(recordings)..orderBy([(r) => OrderingTerm.desc(r.createdAt)]))
          .get();

  /// Finds a single recording by [id].
  Future<Recording?> getRecordingById(String id) =>
      (select(recordings)..where((r) => r.id.equals(id))).getSingleOrNull();

  /// Inserts a new recording.
  Future<int> insertRecording(RecordingsCompanion entry) =>
      into(recordings).insert(entry);

  /// Updates trim range for a recording.
  Future<bool> updateTrim(String id, int startMs, int endMs) async {
    final updated = await (update(recordings)..where((r) => r.id.equals(id)))
        .write(RecordingsCompanion(
      trimStartMs: Value(startMs),
      trimEndMs: Value(endMs),
    ));
    return updated > 0;
  }

  /// Toggles favorite status.
  Future<bool> toggleFavorite(String id, bool isFavorite) async {
    final updated = await (update(recordings)..where((r) => r.id.equals(id)))
        .write(RecordingsCompanion(isFavorite: Value(isFavorite)));
    return updated > 0;
  }

  /// Deletes a recording by [id].
  Future<int> deleteRecording(String id) =>
      (delete(recordings)..where((r) => r.id.equals(id))).go();
}

LazyDatabase _openConnection() {
  return LazyDatabase(() async {
    final dbFolder = await getApplicationDocumentsDirectory();
    final file = File(p.join(dbFolder.path, 'dsr_recordings.sqlite'));
    return NativeDatabase.createInBackground(file);
  });
}
