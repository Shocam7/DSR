import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'engine_api.dart';
import 'pigeon_engine_api.dart';

/// Provides the [EngineApi] implementation.
///
/// In production, the Pigeon-backed [PigeonEngineApi] is used.
/// In tests, override this with [FakeEngineApi] via [ProviderScope.overrides].
final engineApiProvider = Provider<EngineApi>((ref) => PigeonEngineApi());
