/// dsr_engine — DubSmash Reborn native engine plugin.
///
/// This library exposes the Pigeon-generated message types and the plugin
/// registration. Dart code in the main app only touches this through the
/// [EngineApi] interface defined in `lib/engine/engine_api.dart`.
library dsr_engine;

export 'src/messages.g.dart';
