import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_crashlytics/firebase_crashlytics.dart';
import 'package:firebase_remote_config/firebase_remote_config.dart';
import 'package:flutter/foundation.dart';

/// Bootstraps platform services before [runApp].
/// Keep this fast — it runs on the UI thread before the first frame.
Future<void> bootstrap() async {
  // Firebase (Crashlytics + Remote Config).
  // google-services.json is NOT committed; it is injected by CI.
  // In the `dev` flavor we skip Firebase to allow local runs without config.
  const flavor = String.fromEnvironment('APP_FLAVOR', defaultValue: 'dev');
  if (flavor != 'dev') {
    await Firebase.initializeApp();

    // Forward Flutter framework errors to Crashlytics.
    FlutterError.onError = FirebaseCrashlytics.instance.recordFlutterFatalError;
    PlatformDispatcher.instance.onError = (error, stack) {
      FirebaseCrashlytics.instance.recordError(error, stack, fatal: true);
      return true;
    };

    // Fetch Remote Config (non-blocking; cached values used immediately).
    unawaited(
      FirebaseRemoteConfig.instance
          .fetchAndActivate()
          .catchError((_) => false), // network errors must not block startup
    );
  }
}

/// Fire-and-forget helper that satisfies `avoid_void_async` for unawaited Futures.
void unawaited(Future<void> future) {}
