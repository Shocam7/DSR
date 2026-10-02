// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// EnginePlugin — Flutter plugin entry point.
//
// Owner thread: main (Android) thread only.
// All GL and ML work is dispatched to their respective threads from here.
//
// At M0: registers the Pigeon EngineHostApi and the HelloTextureRenderer
// (an animated GL color into a SurfaceProducer — proves the EGL ↔ Flutter
// texture pipe works before camera code is wired).

package com.dubsmash.dsr_engine

import android.os.Handler
import android.os.Looper
import com.dubsmash.dsr_engine.gl.HelloTextureRenderer
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

/**
 * Flutter plugin entry point.
 *
 * Lifecycle:
 *  - [onAttachedToEngine]: register Pigeon APIs, hold references.
 *  - [onDetachedFromEngine]: tear down all sessions, release GL.
 */
class EnginePlugin : FlutterPlugin, ActivityAware {

    private var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private val sessions = mutableMapOf<Int, EngineSession>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── FlutterPlugin ─────────────────────────────────────────────────────────

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        flutterPluginBinding = binding

        // Register the Pigeon HostApi implementation.
        EngineHostApi.setUp(
            binding.binaryMessenger,
            EngineHostApiImpl(
                textureRegistry = binding.textureRegistry,
                binaryMessenger = binding.binaryMessenger,
                sessions = sessions,
            )
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Tear down all live sessions to avoid leaks.
        sessions.values.forEach { it.dispose() }
        sessions.clear()
        EngineHostApi.setUp(binding.binaryMessenger, null)
        flutterPluginBinding = null
    }

    // ── ActivityAware ─────────────────────────────────────────────────────────

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        // Provide the Activity to sessions that need it (CameraX lifecycle).
        sessions.values.forEach { it.onActivityAttached(binding.activity) }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        sessions.values.forEach { it.onActivityDetached() }
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        sessions.values.forEach { it.onActivityAttached(binding.activity) }
    }

    override fun onDetachedFromActivity() {
        sessions.values.forEach { it.onActivityDetached() }
    }
}
