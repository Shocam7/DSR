// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// EngineSession — owns the lifecycle of a single recording session.
//
// Owner thread: main thread for lifecycle methods.
// GL work is on the GL render thread (spawned by the renderer).
// ML work will be on the ML thread (wired in M3).

package com.dubsmash.dsr_engine

import android.app.Activity
import com.dubsmash.dsr_engine.gl.HelloTextureRenderer
import io.flutter.view.TextureRegistry

/**
 * A single engine session. Corresponds to one Flutter Texture widget.
 *
 * At M0 the session wraps [HelloTextureRenderer] only.
 * In M1+ it will own CameraX, the ML thread, theme runtime, etc.
 */
internal class EngineSession(
    val sessionId: Int,
    private val producer: TextureRegistry.SurfaceProducer,
    private val renderer: HelloTextureRenderer,
    private val flutterApi: EngineFlutterApi,
) {
    private var activity: Activity? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onActivityAttached(activity: Activity) {
        this.activity = activity
    }

    fun onActivityDetached() {
        this.activity = null
    }

    // ── Commands (called from EngineHostApiImpl) ───────────────────────────────

    /** Start the GL render loop. In M0: starts the hello-texture animator. */
    fun startPreview() {
        renderer.start()
    }

    /** Stop the render loop without destroying the GL context. */
    fun stopPreview() {
        renderer.stop()
    }

    /** Begin room scan. In M0: no-op; ML is wired in M3. */
    fun startScan() {
        // TODO M3: start ML-thread scan pipeline.
    }

    /** Build and return scene descriptor JSON. In M0: returns stub JSON. */
    fun finishScan(callback: (Result<String>) -> Unit) {
        // TODO M3: build real descriptor.
        callback(
            Result.success(
                """{"schema":1,"track_id":"trk_stub","seed":0,"tier":"M",
"scene":{"surfaces":{"wall":0.5,"floor":0.3,"ceiling":0.1,"window":0.0},
"objects":[],"palette":["#aaaaaa"],"lighting":{"dir":[0.0,-1.0,0.0],
"warmth":0.5,"intensity":0.5},"layout":{"room_size":"medium",
"plane_normals":"estimated"}},"keyframe_consent":false}""".trimIndent()
            )
        )
    }

    /** Apply a downloaded theme bundle. In M0: no-op; theme runtime is in M4. */
    fun applyTheme(themeDir: String, stage: ThemeStage, crossfadeMs: Int) {
        // TODO M4: apply theme via ThemeRuntime.
    }

    /** Override the device tier for debugging / A/B. */
    fun setTierOverride(tier: Tier?) {
        // TODO M4: pass to governor.
    }

    /** Start MediaCodec recording. In M0: no-op; recording is in M2. */
    fun startRecording(config: RecordingConfig, callback: (Result<Unit>) -> Unit) {
        // TODO M2: start MediaCodec encoder.
        callback(Result.success(Unit))
    }

    /** Stop recording and mux. In M0: returns stub result. */
    fun stopRecording(callback: (Result<RecordingResult>) -> Unit) {
        // TODO M2: stop MediaCodec, mux audio+video.
        callback(
            Result.success(
                RecordingResult(
                    outputPath = "/stub/output.mp4",
                    durationMs = 0,
                    themeId = "th_stub",
                )
            )
        )
    }

    /**
     * Release all resources. Must complete within 500 ms.
     * Called from [EngineHostApiImpl.disposeSession].
     */
    fun dispose() {
        renderer.dispose()
        producer.release()
        activity = null
    }
}
