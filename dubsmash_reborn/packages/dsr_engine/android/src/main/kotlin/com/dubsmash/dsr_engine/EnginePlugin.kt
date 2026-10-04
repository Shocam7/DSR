// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// EnginePlugin — Flutter plugin entry point.
//
// Owner thread: main (Android) thread only.
// All GL and ML work is dispatched to their respective threads from here.

package com.dubsmash.dsr_engine

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

/**
 * Flutter plugin entry point.
 *
 * Lifecycle:
 *  - [onAttachedToEngine]: register Pigeon APIs, hold references.
 *  - [onDetachedFromEngine]: tear down all sessions, release GL and camera.
 */
class EnginePlugin : FlutterPlugin, ActivityAware {

    private var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private val sessions = mutableMapOf<Int, EngineSession>()
    private var hostApiImpl: EngineHostApiImpl? = null

    // ── FlutterPlugin ─────────────────────────────────────────────────────────

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        flutterPluginBinding = binding

        val impl = EngineHostApiImpl(
            context = binding.applicationContext,
            textureRegistry = binding.textureRegistry,
            binaryMessenger = binding.binaryMessenger,
            sessions = sessions,
        )
        hostApiImpl = impl

        EngineHostApi.setUp(binding.binaryMessenger, impl)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Tear down all live sessions to avoid leaks.
        sessions.values.forEach { it.dispose() }
        sessions.clear()
        EngineHostApi.setUp(binding.binaryMessenger, null)
        hostApiImpl = null
        flutterPluginBinding = null
    }

    // ── ActivityAware ─────────────────────────────────────────────────────────

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        hostApiImpl?.onActivityAttached(binding.activity)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        hostApiImpl?.onActivityDetached()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        hostApiImpl?.onActivityAttached(binding.activity)
    }

    override fun onDetachedFromActivity() {
        hostApiImpl?.onActivityDetached()
    }
}
