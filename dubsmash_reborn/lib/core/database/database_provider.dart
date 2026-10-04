// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// database_provider — provides singleton AppDatabase instance.

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'app_database.dart';

final databaseProvider = Provider<AppDatabase>((ref) {
  final db = AppDatabase();
  ref.onDispose(db.close);
  return db;
});
