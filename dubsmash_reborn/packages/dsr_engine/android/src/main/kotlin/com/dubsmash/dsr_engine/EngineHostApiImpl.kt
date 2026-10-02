// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// EngineHostApiImpl — implements the Pigeon EngineHostApi.
//
// Owner thread: main thread (Pigeon dispatches here).
// All async work (GL, ML) is delegated to EngineSession.

package com.dubsmash.dsr_engine

import android.app.Activity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.view.TextureRegistry
import com.dubsmash.dsr_engine.gl.HelloTextureRenderer

/**
 * Pigeon [Messages.EngineHostApi] implementation.
 *
 * Creates and delegates to [EngineSession] objects, keyed by [sessionId].
 *
 * At M0: [createSession] spins up a [HelloTextureRenderer] that renders
 * an animated GL colour into a [TextureRegistry.SurfaceProducer] — proving
 * the EGL ↔ Flutter Texture pipe works end-to-end.
 */
internal class EngineHostApiImpl(
    private val textureRegistry: TextureRegistry,
    private val binaryMessenger: BinaryMessenger,
    private val sessions: MutableMap<Int, EngineSession>,
) : EngineHostApi {

    private var currentActivity: Activity? = null

    fun onActivityAttached(activity: Activity) {
        currentActivity = activity
        sessions.values.forEach { it.onActivityAttached(activity) }
    }

    fun onActivityDetached() {
        currentActivity = null
        sessions.values.forEach { it.onActivityDetached() }
    }

    // ── EngineHostApi ─────────────────────────────────────────────────────────

    override fun createSession(
        config: SessionConfig,
        callback: (Result<SessionInfo>) -> Unit,
    ) {
        val sessionId = config.sessionId.toInt()
        if (sessions.containsKey(sessionId)) {
            callback(
                Result.failure(
                    IllegalStateException("Session $sessionId already exists.")
                )
            )
            return
        }

        val producer = textureRegistry.createSurfaceProducer()
        val flutterApi = EngineFlutterApi(binaryMessenger)

        val previewWidth = 1080
        val previewHeight = 1920
        producer.setSize(previewWidth, previewHeight)

        // M0: HelloTextureRenderer — animated GL colour to prove the pipe works.
        // Replaced by the full compositor in M1.
        val renderer = HelloTextureRenderer(producer, flutterApi)

        val session = EngineSession(
            sessionId = sessionId,
            producer = producer,
            renderer = renderer,
            flutterApi = flutterApi,
        )
        sessions[sessionId] = session

        currentActivity?.let { session.onActivityAttached(it) }

        callback(
            Result.success(
                SessionInfo(
                    textureId = producer.id(),
                    previewWidth = previewWidth.toLong(),
                    previewHeight = previewHeight.toLong(),
                    assignedTier = Tier.M,
                )
            )
        )
    }

    override fun startPreview(sessionId: Long, callback: (Result<Unit>) -> Unit) {
        sessions[sessionId.toInt()]?.startPreview()
        callback(Result.success(Unit))
    }

    override fun stopPreview(sessionId: Long) {
        sessions[sessionId.toInt()]?.stopPreview()
    }

    override fun startScan(sessionId: Long) {
        sessions[sessionId.toInt()]?.startScan()
    }

    override fun finishScan(sessionId: Long, callback: (Result<String>) -> Unit) {
        val session = sessions[sessionId.toInt()]
        if (session == null) {
            callback(Result.failure(IllegalStateException("No session $sessionId")))
            return
        }
        session.finishScan(callback)
    }

    override fun applyTheme(
        sessionId: Long,
        themeDir: String,
        stage: ThemeStage,
        crossfadeMs: Long,
    ) {
        sessions[sessionId.toInt()]?.applyTheme(themeDir, stage, crossfadeMs.toInt())
    }

    override fun setTierOverride(sessionId: Long, tier: Tier?) {
        sessions[sessionId.toInt()]?.setTierOverride(tier)
    }

    override fun startRecording(
        sessionId: Long,
        config: RecordingConfig,
        callback: (Result<Unit>) -> Unit,
    ) {
        sessions[sessionId.toInt()]?.startRecording(config, callback)
            ?: callback(Result.failure(IllegalStateException("No session $sessionId")))
    }

    override fun stopRecording(
        sessionId: Long,
        callback: (Result<RecordingResult>) -> Unit,
    ) {
        sessions[sessionId.toInt()]?.stopRecording(callback)
            ?: callback(Result.failure(IllegalStateException("No session $sessionId")))
    }

    override fun disposeSession(sessionId: Long) {
        sessions.remove(sessionId.toInt())?.dispose()
    }
}
