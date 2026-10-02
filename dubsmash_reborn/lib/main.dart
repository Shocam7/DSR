// This file is intentionally minimal at M0.
// It bootstraps the app and hands off to the router.
// Business logic lives in features/, not here.

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app/app.dart';
import 'core/bootstrap.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await bootstrap();
  runApp(const ProviderScope(child: DubSmashApp()));
}
