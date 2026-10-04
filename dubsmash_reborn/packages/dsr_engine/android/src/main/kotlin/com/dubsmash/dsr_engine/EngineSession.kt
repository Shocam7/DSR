// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// EngineSession — coordinates CameraX, GL Compositor, and native pipeline
// for a single recording session.
// M3: Integrates ML pipeline (ModelLoader, DelegateChooser, PersonSegmentor) and Governor.

package com.dubsmash.dsr_engine

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.dubsmash.dsr_engine.audio.AudioCaptureAndEncoder
import com.dubsmash.dsr_engine.audio.AudioRouteManager
import com.dubsmash.dsr_engine.camera.CameraPipeline
import com.dubsmash.dsr_engine.camera.PluginLifecycleOwner
import com.dubsmash.dsr_engine.encoder.MediaMuxerManager
import com.dubsmash.dsr_engine.encoder.VideoEncoder
import com.dubsmash.dsr_engine.gl.GlCompositor
import com.dubsmash.dsr_engine.governor.Governor
import com.dubsmash.dsr_engine.governor.GovernorListener
import com.dubsmash.dsr_engine.governor.GovernorInput
import com.dubsmash.dsr_engine.governor.RenderQuality
import com.dubsmash.dsr_engine.ml.DelegateChooser
import com.dubsmash.dsr_engine.ml.ModelLoader
import com.dubsmash.dsr_engine.ml.PersonSegmentor
import com.dubsmash.dsr_engine.scene.SceneSegmentor
import com.dubsmash.dsr_engine.theme.ThemeRuntime
import io.flutter.view.TextureRegistry
import java.io.File

/**
 * A single engine session corresponding to one Flutter Texture widget.
 *
 * M1 Architecture:
 *  - [PluginLifecycleOwner]: plugin-owned LifecycleOwner driven by Activity + session
 *  - [CameraPipeline]: CameraX Preview pipeline feeding external GL surface
 *  - [GlCompositor]: EGL context, external OES texture, compositor FBO, and blit to Flutter SurfaceProducer
 *
 * M3 Additions:
 *  - [ModelLoader] + [DelegateChooser]: TFLite model loading with checksum + NPU/GPU/CPU delegate selection
 *  - [PersonSegmentor]: On-device person segmentation on dedicated ML thread
 *  - [Governor]: Frame time + thermal state → render quality decisions
 */
internal class EngineSession(
    val sessionId: Int,
    private val context: Context,
    private val producer: TextureRegistry.SurfaceProducer,
    private val flutterApi: EngineFlutterApi,
    initialIsFrontCamera: Boolean = true,
    previewWidth: Int = 1080,
    previewHeight: Int = 1920,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleOwner = PluginLifecycleOwner()

    private val compositor = GlCompositor(
        producer = producer,
        previewWidth = previewWidth,
        previewHeight = previewHeight,
        flutterApi = flutterApi,
    )

    private val cameraPipeline = CameraPipeline(
        context = context,
        sessionId = sessionId,
        lifecycleOwner = lifecycleOwner,
        flutterApi = flutterApi,
        initialIsFrontCamera = initialIsFrontCamera,
    )

    private val audioRouteManager = AudioRouteManager(context) { isHeadphones, disconnected ->
        val payload = """{"event":"audio_route_changed","is_headphones":$isHeadphones,"disconnected":$disconnected}"""
        flutterApi.onEvent(
            EngineEvent(
                sessionId = sessionId.toLong(),
                type = EngineEventType.ERROR,
                errorCode = null,
                payload = payload,
            )
        ) {}
    }

    // ── M3: Governor ──────────────────────────────────────────────────────────
    private val governor = Governor(object : GovernorListener {
        override fun onQualityChanged(quality: RenderQuality) {
            Log.i(TAG, "Governor quality changed: $quality")
            // Apply quality decisions: MINIMAL disables ML, REDUCED softens edge
            when (quality) {
                RenderQuality.FULL -> {
                    compositor.setMaskEnabled(mlEnabled)
                    compositor.setMaskSmoothEdge(0.15f)
                }
                RenderQuality.REDUCED -> {
                    compositor.setMaskEnabled(mlEnabled)
                    compositor.setMaskSmoothEdge(0.05f) // Simpler edge
                }
                RenderQuality.MINIMAL -> {
                    compositor.setMaskEnabled(false) // Disable ML compositing
                }
            }
        }
    })

    // ── M3: ML pipeline state ─────────────────────────────────────────────────
    private var personSegmentor: PersonSegmentor? = null
    private var mlEnabled = false
    private val mlInitThread = HandlerThread("dsr-ml-init").apply { start() }
    private val mlInitHandler = Handler(mlInitThread.looper)

    private var videoEncoder: VideoEncoder? = null
    private var audioCaptureAndEncoder: AudioCaptureAndEncoder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var muxerManager: MediaMuxerManager? = null
    private var activeRecordingConfig: RecordingConfig? = null
    private var recordingStartTimeMs: Long = 0L
    private var currentThemeId: String = "default"
    private var sessionTier: Tier = Tier.M
    private val themeRuntime = ThemeRuntime(context, compositor, sessionTier)
    private val sceneSegmentor = SceneSegmentor(sessionTier)

    private var activity: Activity? = null

    companion object {
        private const val TAG = "EngineSession"
    }

    init {
        // Wire compositor's camera surface into CameraPipeline
        compositor.onCameraSurfaceReady = { surface ->
            cameraPipeline.setSurface(surface, previewWidth, previewHeight)
        }
        val existingSurf = compositor.cameraSurface
        if (existingSurf != null && existingSurf.isValid) {
            cameraPipeline.setSurface(existingSurf, previewWidth, previewHeight)
        }
        cameraPipeline.onUpdateBufferSize = { w, h ->
            compositor.updateCameraBufferSize(w, h)
        }

        // M4: Wire scene segmentor into compositor
        compositor.sceneSegmentor = sceneSegmentor

        // M3: Initialize ML pipeline asynchronously (non-blocking)
        initMlPipelineAsync()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onActivityAttached(activity: Activity) {
        this.activity = activity
    }

    fun onActivityDetached() {
        this.activity = null
        cameraPipeline.stop()
        compositor.stop()
    }

    // ── M3: ML pipeline initialization ───────────────────────────────────────

    /**
     * Initializes TFLite model + delegate asynchronously on a background thread.
     * Uses ModelLoader (checksum verification) → DelegateChooser (NPU/GPU/CPU benchmark).
     * Sets [compositor.personSegmentor] when ready.
     *
     * Safe to call from init — never blocks the main thread.
     */
    private fun initMlPipelineAsync() {
        mlInitHandler.post {
            try {
                // M3-T1: Load model with checksum verification
                // NOTE: checksum is PLACEHOLDER until real model file is bundled.
                // Production: update ModelLoader.MODEL_SELFIE_SEG_SHA256 with real SHA-256.
                val modelBuffer = try {
                    ModelLoader.loadModel(context, "selfie_segmentation.tflite")
                } catch (e: Exception) {
                    Log.w(TAG, "ML model load skipped (placeholder or missing): ${e.message}")
                    return@post
                }

                // M3-T2: Choose best delegate with cached benchmark
                val delegateResult = try {
                    DelegateChooser.chooseDelegate(context, modelBuffer)
                } catch (e: Exception) {
                    Log.w(TAG, "Delegate selection failed, ML disabled: ${e.message}")
                    return@post
                }

                Log.i(TAG, "ML delegate chosen: ${delegateResult.delegateType}, latency=${delegateResult.latencyMs}ms")

                // M3-T3: Wire chosen PersonSegmentor into compositor
                val segmentor = delegateResult.segmentor
                personSegmentor = segmentor
                compositor.personSegmentor = segmentor

                Log.i(TAG, "M3 ML pipeline ready: ${delegateResult.delegateType}")
            } catch (e: Exception) {
                Log.e(TAG, "M3 ML pipeline init failed", e)
            }
        }
    }

    // ── M3: Background effect controls ───────────────────────────────────────

    /**
     * Set background effect parameters from Pigeon.
     */
    fun setBackgroundEffect(enabled: Boolean, useBlur: Boolean, colorArgb: Int, blurRadius: Int) {
        mlEnabled = enabled
        val a = ((colorArgb shr 24) and 0xFF) / 255.0f
        val r = ((colorArgb shr 16) and 0xFF) / 255.0f
        val g = ((colorArgb shr 8) and 0xFF) / 255.0f
        val b = (colorArgb and 0xFF) / 255.0f

        compositor.setMaskUseBlur(useBlur)
        compositor.setMaskBgColor(r, g, b)
        compositor.setMaskBlurRadius(blurRadius)

        if (governor.getQuality() != com.dubsmash.dsr_engine.governor.RenderQuality.MINIMAL) {
            compositor.setMaskEnabled(enabled)
        }
    }

    /**
     * Enable person segmentation and background replacement.
     * Also feeds Governor with current frame stats.
     */
    fun setMaskEnabled(enabled: Boolean) {
        mlEnabled = enabled
        if (governor.getQuality() != com.dubsmash.dsr_engine.governor.RenderQuality.MINIMAL) {
            compositor.setMaskEnabled(enabled)
        }
    }

    /** Switch between solid color background (false) and blur background (true). */
    fun setMaskUseBlur(useBlur: Boolean) = compositor.setMaskUseBlur(useBlur)

    /** Set background color (r, g, b in 0..1). */
    fun setMaskBgColor(r: Float, g: Float, b: Float) = compositor.setMaskBgColor(r, g, b)

    /** Set background blur radius (1..8). */
    fun setMaskBlurRadius(radius: Int) = compositor.setMaskBlurRadius(radius)

    /** Feed Governor with latest frame timing for quality management. */
    fun feedGovernor(frameTimeMs: Double, thermalStatus: Int) {
        governor.feed(GovernorInput(frameTimeMs, thermalStatus))
    }

    // ── Commands ──────────────────────────────────────────────────────────────


    /**
     * Start CameraX preview and GL render loop.
     */
    fun startPreview() {
        compositor.start()
        val surface = compositor.cameraSurface
        if (surface != null && surface.isValid) {
            cameraPipeline.setSurface(surface, 1080, 1920)
        }
        cameraPipeline.start()
    }

    /**
     * Pause preview and release camera without destroying GL context.
     */
    fun stopPreview() {
        cameraPipeline.stop()
        compositor.stop()
    }

    /**
     * Switch between front and back cameras.
     */
    fun switchCamera(callback: (Result<Unit>) -> Unit) {
        cameraPipeline.switchCamera { isFront ->
            callback(Result.success(Unit))
        }
    }

    /**
     * Begin room scan (M3).
     */
    fun startScan() {
        // TODO M3: start ML-thread scan pipeline.
    }

    /**
     * Finalize scan and return scene descriptor (M3).
     */
    fun finishScan(callback: (Result<String>) -> Unit) {
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

    /**
     * Apply theme bundle (M4).
     */
    fun applyTheme(themeDir: String, stage: ThemeStage, crossfadeMs: Int) {
        if (themeDir.isBlank()) {
            compositor.setSurfaceCompositorEnabled(false)
            currentThemeId = "default"
            return
        }
        val dir = File(themeDir)
        themeRuntime.applyTheme(
            themeDir = dir,
            stage = stage,
            crossfadeMs = crossfadeMs,
            onSuccess = { manifest ->
                currentThemeId = manifest.themeId
                val payload = """{"theme_id":"${manifest.themeId}","stage":"${stage.name.lowercase()}"}"""
                mainHandler.post {
                    flutterApi.onEvent(
                        EngineEvent(
                            sessionId = sessionId.toLong(),
                            type = EngineEventType.THEME_STAGE_READY,
                            errorCode = null,
                            payload = payload,
                        )
                    ) {}
                }
            },
            onError = { e ->
                Log.e(TAG, "applyTheme failed: ${e.message}", e)
                mainHandler.post {
                    flutterApi.onEvent(
                        EngineEvent(
                            sessionId = sessionId.toLong(),
                            type = EngineEventType.ERROR,
                            errorCode = EngineErrorCode.THEME_INVALID,
                            payload = e.message,
                        )
                    ) {}
                }
            }
        )
    }

    /**
     * Override device tier.
     */
    fun setTierOverride(tier: Tier?) {
        if (tier != null) {
            sessionTier = tier
            themeRuntime.setTier(tier)
            sceneSegmentor.tier = tier
        }
    }

    /**
     * Start MediaCodec recording (M2).
     */
    fun startRecording(config: RecordingConfig, callback: (Result<Unit>) -> Unit) {
        Log.i("EngineSession", "startRecording called: path=${config.outputPath}, audioTrack=${config.audioTrackPath}")
        try {
            val width = if (config.width > 0) config.width.toInt() else 1080
            val height = if (config.height > 0) config.height.toInt() else 1920
            val bitrate = if (config.videoBitrateBps > 0) config.videoBitrateBps.toInt() else 10_000_000

            val muxer = MediaMuxerManager(config.outputPath, expectedTracks = 2)
            muxerManager = muxer

            val vEncoder = VideoEncoder(
                width = width,
                height = height,
                bitrateBps = bitrate,
                frameRate = 30,
                iFrameIntervalSecs = 1,
                muxerManager = muxer,
            )
            videoEncoder = vEncoder

            val aEncoder = AudioCaptureAndEncoder(
                muxerManager = muxer,
                routeManager = audioRouteManager,
                audioOffsetMs = config.audioOffsetMs.toInt(),
                backingAudioPath = config.audioTrackPath,
            )
            audioCaptureAndEncoder = aEncoder

            activeRecordingConfig = config
            recordingStartTimeMs = SystemClock.elapsedRealtime()

            compositor.startRecording(vEncoder.inputSurface, width, height)
            vEncoder.start()
            aEncoder.start()

            // Start background audio playback out loud if provided
            val audioTrack = config.audioTrackPath
            if (!audioTrack.isNullOrEmpty()) {
                try {
                    val player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        setDataSource(audioTrack)
                        isLooping = true
                        setVolume(1.0f, 1.0f)
                        prepare()
                        start()
                    }
                    mediaPlayer = player
                    Log.i("EngineSession", "Background audio player started for $audioTrack (duration=${player.duration}ms)")
                } catch (e: Exception) {
                    Log.e("EngineSession", "Failed to start background audio playback for $audioTrack", e)
                }
            }

            flutterApi.onEvent(
                EngineEvent(
                    sessionId = sessionId.toLong(),
                    type = EngineEventType.RECORDING_STARTED,
                )
            ) {}

            callback(Result.success(Unit))
        } catch (e: Exception) {
            Log.e("EngineSession", "Failed to start recording", e)
            callback(Result.failure(e))
        }
    }

    /**
     * Stop recording and mux (M2).
     */
    fun stopRecording(callback: (Result<RecordingResult>) -> Unit) {
        try {
            val durationMs = if (recordingStartTimeMs > 0L) {
                SystemClock.elapsedRealtime() - recordingStartTimeMs
            } else {
                0L
            }
            val outputPath = activeRecordingConfig?.outputPath ?: "/sdcard/output.mp4"

            mediaPlayer?.let { player ->
                try {
                    if (player.isPlaying) {
                        player.stop()
                    }
                    player.release()
                } catch (e: Exception) {
                    Log.e("EngineSession", "Error releasing MediaPlayer", e)
                }
            }
            mediaPlayer = null

            compositor.stopRecording()
            videoEncoder?.stop()
            videoEncoder = null
            audioCaptureAndEncoder?.stop()
            audioCaptureAndEncoder = null
            muxerManager?.release()
            muxerManager = null
            activeRecordingConfig = null

            flutterApi.onEvent(
                EngineEvent(
                    sessionId = sessionId.toLong(),
                    type = EngineEventType.RECORDING_STOPPED,
                )
            ) {}

            callback(
                Result.success(
                    RecordingResult(
                        outputPath = outputPath,
                        durationMs = durationMs,
                        themeId = currentThemeId,
                    )
                )
            )
        } catch (e: Exception) {
            Log.e("EngineSession", "Failed to stop recording", e)
            callback(Result.failure(e))
        }
    }

    /**
     * Release all resources within 500 ms.
     */
    fun dispose() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
                player.release()
            } catch (e: Exception) {
                Log.e("EngineSession", "Error releasing MediaPlayer in dispose", e)
            }
        }
        mediaPlayer = null

        if (videoEncoder != null || audioCaptureAndEncoder != null) {
            compositor.stopRecording()
            videoEncoder?.stop()
            videoEncoder = null
            audioCaptureAndEncoder?.stop()
            audioCaptureAndEncoder = null
            muxerManager?.release()
            muxerManager = null
        }
        audioRouteManager.dispose()
        cameraPipeline.dispose()
        compositor.dispose()
        themeRuntime.dispose()
        // M4: Dispose SceneSegmentor
        compositor.sceneSegmentor = null
        sceneSegmentor.dispose()
        // M3: Dispose ML pipeline and init thread
        compositor.personSegmentor = null
        personSegmentor?.dispose()
        personSegmentor = null
        mlInitThread.quitSafely()
        governor.reset()
        producer.release()
        activity = null
    }
}
