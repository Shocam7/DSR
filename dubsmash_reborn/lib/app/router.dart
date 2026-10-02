import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/record/presentation/record_screen.dart';

/// Application router provider.
/// At M0 we only have the record/preview screen. Screens are added
/// milestone by milestone, keeping this file as the single source of routes.
final routerProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    initialLocation: '/record',
    routes: [
      GoRoute(
        path: '/record',
        builder: (context, state) => const RecordScreen(),
      ),
    ],
    observers: const [],
  );
});
