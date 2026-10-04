// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// GlCompositor — OpenGL ES 2.0 / 3.0 compositor for DSR.
// Owns the EGL context, render thread, camera external OES texture,
// compositor FBO (passthrough shader in M1), and blit to Flutter SurfaceProducer.
// M3: Integrates MaskCompositor (person segmentation) and TemporalSmoother.

package com.dubsmash.dsr_engine.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.dubsmash.dsr_engine.EngineFlutterApi
import com.dubsmash.dsr_engine.FrameStats
import com.dubsmash.dsr_engine.Tier
import com.dubsmash.dsr_engine.ml.PersonSegmentor
import com.dubsmash.dsr_engine.ml.PixelExtractor
import com.dubsmash.dsr_engine.scene.SceneSegmentor
import com.dubsmash.dsr_engine.theme.SurfaceCompositor
import io.flutter.view.TextureRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GL Compositor owning EGL context, thread, and shaders.
 *
 * M1 Pipeline:
 *  CameraX -> SurfaceTexture(OES) -> Compositor FBO (passthrough) -> Flutter SurfaceProducer
 */
class GlCompositor(
    private val producer: TextureRegistry.SurfaceProducer,
    private val previewWidth: Int = 1080,
    private val previewHeight: Int = 1920,
    private val flutterApi: EngineFlutterApi? = null,
) : TextureRegistry.SurfaceProducer.Callback {

    companion object {
        private const val TAG = "DsrGlCompositor"

        // Fullscreen quad NDC coordinates
        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
        )

        // Texture coordinates for OES
        private val OES_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
        )

        // Texture coordinates for 2D blit (standard OpenGL UV)
        private val BLIT_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
        )

        // Texture coordinates for ML FBO blit (V flipped so lower row of ML FBO gets top of camera frame).
        // When glReadPixels reads bottom-up into Bitmap, the resulting Bitmap is upright.
        private val ML_TEX_COORDS = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
        )

        private const val VERTEX_SHADER_OES = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uSTMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private const val VERTEX_SHADER_BLIT = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER_BLIT = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }

    // ── Dedicated GL thread ──────────────────────────────────────────────────
    private val glThread = HandlerThread("dsr-gl").also { it.start() }
    val glHandler = Handler(glThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── EGL state ─────────────────────────────────────────────────────────────
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglPbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderWidth: Int = 1080
    private var encoderHeight: Int = 1920
    private val isRecordingActive = AtomicBoolean(false)
    private var recordingStartNs = 0L

    // ── OES Camera Texture & SurfaceTexture ──────────────────────────────────
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    var cameraSurface: Surface? = null
        private set

    // ── Compositor FBO ────────────────────────────────────────────────────────
    private var fboId = 0
    private var fboTextureId = 0

    // ── Shaders & Buffers ─────────────────────────────────────────────────────
    private var oesProgram = 0
    private var oesPosHandle = 0
    private var oesTexHandle = 0
    private var oesMatrixHandle = 0
    private var oesSamplerHandle = 0

    private var blitProgram = 0
    private var blitPosHandle = 0
    private var blitTexHandle = 0
    private var blitSamplerHandle = 0

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_COORDS)
            position(0)
        }

    private val oesTexBuffer: FloatBuffer = ByteBuffer.allocateDirect(OES_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(OES_TEX_COORDS)
            position(0)
        }

    private val blitTexBuffer: FloatBuffer = ByteBuffer.allocateDirect(BLIT_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(BLIT_TEX_COORDS)
            position(0)
        }

    private val mlTexBuffer: FloatBuffer = ByteBuffer.allocateDirect(ML_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(ML_TEX_COORDS)
            position(0)
        }

    private val stMatrix = FloatArray(16)

    // ── State flags & metrics ─────────────────────────────────────────────────
    private val isRunning = AtomicBoolean(false)
    private val isDisposed = AtomicBoolean(false)
    @Volatile
    private var frameAvailable = false

    private var frameCount = 0
    private var lastStatsTimeMs = 0L
    private var totalGlRenderMs = 0.0

    // ── M3: Mask compositor and temporal smoother ─────────────────────────────
    private val maskCompositor = MaskCompositor()
    private val temporalSmoother = TemporalSmoother()

    // ── M4: Surface compositor for theme retexturing & crossfade ───────────────
    val surfaceCompositor = SurfaceCompositor()

    /** Set by EngineSession after ML pipeline is initialized. */
    @Volatile
    var personSegmentor: PersonSegmentor? = null

    /** M4: Set by EngineSession for scene segmentation. */
    @Volatile
    var sceneSegmentor: SceneSegmentor? = null

    private var sceneCadenceCounter = 0
    private var lastUploadedSceneMaskTimestampNs = 0L

    // ── M3 FBO for composited output ──────────────────────────────────────────
    private var compositeFboId = 0
    private var compositeFboTextureId = 0

    // ── M3 Downscaled aspect-matched FBO for ML input ─────────────────────────
    private var mlWidth = 144
    private var mlHeight = 256
    private var mlFboId = 0
    private var mlFboTextureId = 0

    private fun ensureMlFboSize(targetW: Int, targetH: Int) {
        if (targetW == mlWidth && targetH == mlHeight && mlFboId != 0) return
        mlWidth = targetW
        mlHeight = targetH
        if (mlFboTextureId != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mlFboTextureId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, mlWidth, mlHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
    }

    // Callback when camera surface is ready
    var onCameraSurfaceReady: ((Surface) -> Unit)? = null


    init {
        producer.setCallback(this)
        val initLatch = CountDownLatch(1)
        glHandler.post {
            try {
                initEgl()
                initGlResources()
                val surface = producer.surface
                if (surface != null && surface.isValid) {
                    createWindowSurface(surface)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing GL compositor", e)
            } finally {
                initLatch.countDown()
            }
        }
        initLatch.await(500, TimeUnit.MILLISECONDS)
    }

    fun start() {
        if (isDisposed.get()) return
        isRunning.set(true)
        glHandler.post {
            val surface = producer.surface
            if (eglSurface == EGL14.EGL_NO_SURFACE && surface != null && surface.isValid) {
                createWindowSurface(surface)
            }
            renderFrame()
        }
    }

    fun stop() {
        isRunning.set(false)
        glHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Updates the camera SurfaceTexture buffer size to match the CameraX output resolution.
     * Prevents buffer aspect-ratio distortion and vertical stretching.
     */
    fun updateCameraBufferSize(width: Int, height: Int) {
        glHandler.post {
            Log.i(TAG, "Updating surfaceTexture buffer size to ${width}x${height}")
            surfaceTexture?.setDefaultBufferSize(width, height)
        }
    }

    /**
     * Connects an encoder input surface to receive composited frames (M2-T1).
     */
    fun startRecording(surface: Surface, width: Int, height: Int) {
        val latch = CountDownLatch(1)
        glHandler.post {
            try {
                encoderWidth = width
                encoderHeight = height
                recordingStartNs = 0L
                val config = eglConfig
                if (config != null) {
                    val attribs = intArrayOf(EGL14.EGL_NONE)
                    if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                    }
                    encoderEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, attribs, 0)
                    isRecordingActive.set(encoderEglSurface != EGL14.EGL_NO_SURFACE)
                    Log.i(TAG, "Recording EGL surface created: active=${isRecordingActive.get()}")
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await(500, TimeUnit.MILLISECONDS)
    }

    /**
     * Disconnects the encoder input surface.
     */
    fun stopRecording() {
        isRecordingActive.set(false)
        glHandler.post {
            recordingStartNs = 0L
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
                Log.i(TAG, "Recording EGL surface destroyed")
            }
        }
    }

    fun dispose() {
        if (isDisposed.getAndSet(true)) return
        isRunning.set(false)
        glHandler.removeCallbacksAndMessages(null)

        val latch = CountDownLatch(1)
        glHandler.post {
            try {
                cameraSurface?.release()
                cameraSurface = null

                surfaceTexture?.release()
                surfaceTexture = null

                destroyGlResources()
                destroyWindowSurface()
                destroyEglContext()
            } catch (e: Exception) {
                Log.e(TAG, "Error tearing down GL compositor", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(400, TimeUnit.MILLISECONDS)
        glThread.quitSafely()
    }

    // ── TextureRegistry.SurfaceProducer.Callback ──────────────────────────────

    override fun onSurfaceCreated() {
        glHandler.post {
            val surface = producer.surface
            if (surface != null && surface.isValid) {
                createWindowSurface(surface)
            }
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java", ReplaceWith("onSurfaceCreated()"))
    override fun onSurfaceAvailable() {
        onSurfaceCreated()
    }

    override fun onSurfaceDestroyed() {
        glHandler.post {
            destroyWindowSurface()
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java", ReplaceWith("onSurfaceDestroyed()"))
    override fun onSurfaceCleanup() {
        onSurfaceDestroyed()
    }

    // ── Internal EGL / GL Management ──────────────────────────────────────────

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Failed to get EGL display" }

        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0] ?: error("No EGL config found")
        eglConfig = config

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Failed to create EGL context" }

        // Create a 1x1 offscreen Pbuffer surface so the EGLContext is immediately current
        val pbufAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE,
        )
        eglPbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, pbufAttribs, 0)
        check(eglPbufferSurface != EGL14.EGL_NO_SURFACE) { "Failed to create EGL Pbuffer surface" }

        val ok = EGL14.eglMakeCurrent(eglDisplay, eglPbufferSurface, eglPbufferSurface, eglContext)
        check(ok) { "Failed to make EGL context current with Pbuffer surface: ${EGL14.eglGetError()}" }
        Log.i(TAG, "EGL initialized successfully with Pbuffer surface current on dsr-gl thread")
    }

    private fun createWindowSurface(surface: Surface) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) return
        if (!surface.isValid) return
        destroyWindowSurface()

        val config = eglConfig ?: return
        val attribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, attribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "Failed to create EGL window surface: ${EGL14.eglGetError()}")
            return
        }
        val ok = EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        if (!ok) {
            Log.e(TAG, "Failed to make window surface current: ${EGL14.eglGetError()}")
        } else {
            Log.i(TAG, "EGL window surface created and made current")
        }
    }

    private fun destroyWindowSurface() {
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            encoderEglSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            // Restore Pbuffer surface so EGL context remains current
            if (eglPbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, eglPbufferSurface, eglPbufferSurface, eglContext)
            } else {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
            Log.i(TAG, "EGL window surface destroyed")
        }
    }

    private fun destroyEglContext() {
        if (eglPbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglPbufferSurface)
            eglPbufferSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$op: glError 0x${Integer.toHexString(error)}")
        }
    }

    private fun initGlResources() {
        // Create OES Texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        checkGlError("glGenTextures OES")

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        checkGlError("init OES texture parameters")

        val st = SurfaceTexture(oesTextureId)
        st.setDefaultBufferSize(previewWidth, previewHeight)
        st.setOnFrameAvailableListener({
            frameAvailable = true
            if (isRunning.get()) {
                glHandler.post { renderFrame() }
            }
        }, glHandler)
        surfaceTexture = st
        val surf = Surface(st)
        cameraSurface = surf
        mainHandler.post { onCameraSurfaceReady?.invoke(surf) }

        // Create Compositor FBO
        val fbos = IntArray(1)
        val fboTexs = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        GLES20.glGenTextures(1, fboTexs, 0)
        fboId = fbos[0]
        fboTextureId = fboTexs[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            previewWidth, previewHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTextureId, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete: 0x${Integer.toHexString(status)}" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        checkGlError("init FBO")

        // Compile OES Shader Program
        oesProgram = createProgram(VERTEX_SHADER_OES, FRAGMENT_SHADER_OES)
        oesPosHandle = GLES20.glGetAttribLocation(oesProgram, "aPosition")
        oesTexHandle = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
        oesMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uSTMatrix")
        oesSamplerHandle = GLES20.glGetUniformLocation(oesProgram, "sTexture")

        // Compile Blit Shader Program
        blitProgram = createProgram(VERTEX_SHADER_BLIT, FRAGMENT_SHADER_BLIT)
        blitPosHandle = GLES20.glGetAttribLocation(blitProgram, "aPosition")
        blitTexHandle = GLES20.glGetAttribLocation(blitProgram, "aTexCoord")
        blitSamplerHandle = GLES20.glGetUniformLocation(blitProgram, "sTexture")

        // M3: Create composite FBO (output after mask compositing)
        val compositeFbos = IntArray(1)
        val compositeTexs = IntArray(1)
        GLES20.glGenFramebuffers(1, compositeFbos, 0)
        GLES20.glGenTextures(1, compositeTexs, 0)
        compositeFboId = compositeFbos[0]
        compositeFboTextureId = compositeTexs[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, compositeFboTextureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, previewWidth, previewHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, compositeFboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, compositeFboTextureId, 0)
        val compStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (compStatus != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "Composite FBO incomplete: 0x${Integer.toHexString(compStatus)}")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // M3: Create downscaled ML FBO (aspect-matched, e.g. 144x256)
        mlHeight = 256
        mlWidth = ((mlHeight * previewWidth.toFloat() / previewHeight.toFloat()).toInt() and 0xFFFE).coerceAtLeast(128)
        val mlFbos = IntArray(1)
        val mlTexs = IntArray(1)
        GLES20.glGenFramebuffers(1, mlFbos, 0)
        GLES20.glGenTextures(1, mlTexs, 0)
        mlFboId = mlFbos[0]
        mlFboTextureId = mlTexs[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mlFboTextureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, mlWidth, mlHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mlFboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, mlFboTextureId, 0)
        val mlStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (mlStatus != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "ML FBO incomplete: 0x${Integer.toHexString(mlStatus)}")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // M3: Initialize MaskCompositor shaders
        maskCompositor.init(quadBuffer, blitTexBuffer)

        // M4: Initialize SurfaceCompositor shaders
        surfaceCompositor.init(quadBuffer, blitTexBuffer)

        Log.i(TAG, "GL resources initialized successfully (oesTex=$oesTextureId, fbo=$fboId, compositeFbo=$compositeFboId, mlFbo=$mlFboId)")
    }


    private fun destroyGlResources() {
        if (oesProgram != 0) {
            GLES20.glDeleteProgram(oesProgram)
            oesProgram = 0
        }
        if (blitProgram != 0) {
            GLES20.glDeleteProgram(blitProgram)
            blitProgram = 0
        }
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (fboTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
            fboTextureId = 0
        }
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        // M3: Composite FBO, ML FBO, and MaskCompositor
        if (compositeFboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(compositeFboId), 0)
            compositeFboId = 0
        }
        if (compositeFboTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(compositeFboTextureId), 0)
            compositeFboTextureId = 0
        }
        if (mlFboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(mlFboId), 0)
            mlFboId = 0
        }
        if (mlFboTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(mlFboTextureId), 0)
            mlFboTextureId = 0
        }
        maskCompositor.dispose()
        surfaceCompositor.dispose()
    }

    private fun renderFrame() {
        if (!isRunning.get() || isDisposed.get()) return

        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            val surface = producer.surface
            if (surface != null && surface.isValid) {
                createWindowSurface(surface)
            }
            if (eglSurface == EGL14.EGL_NO_SURFACE) return
        }

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        val st = surfaceTexture ?: return
        if (frameAvailable) {
            try {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                frameAvailable = false
            } catch (e: Exception) {
                Log.w(TAG, "updateTexImage failed: ${e.message}")
                return
            }
        }

        val renderStartNs = System.nanoTime()

        // ── Pass 1: Camera OES → Compositor FBO (passthrough) ────────────────
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, previewWidth, previewHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(oesProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(oesSamplerHandle, 0)
        GLES20.glUniformMatrix4fv(oesMatrixHandle, 1, false, stMatrix, 0)

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(oesPosHandle)
        GLES20.glVertexAttribPointer(oesPosHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)

        oesTexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(oesTexHandle)
        GLES20.glVertexAttribPointer(oesTexHandle, 2, GLES20.GL_FLOAT, false, 0, oesTexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(oesPosHandle)
        GLES20.glDisableVertexAttribArray(oesTexHandle)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        // ── M3 & M4 Pass 1.5: ML pixel readback → PersonSegmentor & SceneSegmentor (async, non-blocking) ──
        val pSeg = personSegmentor
        val sSeg = sceneSegmentor
        val needPerson = pSeg != null && maskCompositor.isEnabled()
        val needScene = sSeg != null && surfaceCompositor.isEnabled()

        val pReady = needPerson && !pSeg.isBusy()
        val sCadence = if (sSeg != null) SceneSegmentor.getCadenceForTier(sSeg.tier) else 3
        val sCadenceHit = (sceneCadenceCounter % sCadence == 0)
        val sReady = needScene && !sSeg.isBusy() && sCadenceHit
        if (needScene) {
            sceneCadenceCounter++
        } else {
            sceneCadenceCounter = 0
        }

        if (pReady || sReady) {
            val isMotion = temporalSmoother.lastMotionDelta > 0.035f
            val baseH = if (needScene) {
                SceneSegmentor.getResolutionForTier(sSeg?.tier ?: Tier.M)
            } else {
                if (isMotion) 224 else 256
            }
            val targetH = baseH
            val targetW = ((targetH * previewWidth.toFloat() / previewHeight.toFloat()).toInt() and 0xFFFE).coerceAtLeast(112)
            ensureMlFboSize(targetW, targetH)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mlFboId)
            GLES20.glViewport(0, 0, mlWidth, mlHeight)
            GLES20.glUseProgram(blitProgram)
            quadBuffer.position(0)
            GLES20.glEnableVertexAttribArray(blitPosHandle)
            GLES20.glVertexAttribPointer(blitPosHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)

            mlTexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(blitTexHandle)
            GLES20.glVertexAttribPointer(blitTexHandle, 2, GLES20.GL_FLOAT, false, 0, mlTexBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
            GLES20.glUniform1i(blitSamplerHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(blitPosHandle)
            GLES20.glDisableVertexAttribArray(blitTexHandle)

            // Read full downscaled frame as RGBA
            val rgba = PixelExtractor.readRgba(mlWidth, mlHeight)
            if (rgba.isNotEmpty()) {
                val nowNs = System.nanoTime()
                if (pReady) {
                    pSeg.submitFrame(rgba, mlWidth, mlHeight, nowNs)
                }
                if (sReady) {
                    sSeg.submitFrame(rgba, mlWidth, mlHeight, nowNs)
                }
            }
        }

        // Apply latest available person mask (lock-free AtomicReference read)
        if (needPerson) {
            val maskResult = pSeg.getMask()
            if (maskResult != null) {
                val smoothed = temporalSmoother.smooth(maskResult.maskPixels)
                maskCompositor.updateMask(smoothed, maskResult.width, maskResult.height)
            }
        }

        // Apply latest available scene mask (lock-free AtomicReference read)
        if (needScene) {
            val sceneResult = sSeg.getLatestMask()
            if (sceneResult != null && sceneResult.timestampNs != lastUploadedSceneMaskTimestampNs) {
                lastUploadedSceneMaskTimestampNs = sceneResult.timestampNs
                surfaceCompositor.updateSceneMask(sceneResult.maskRgba, sceneResult.width, sceneResult.height)
            }
        }

        // ── M4 Pass 2a: SurfaceCompositor (Surfaces retexturing & lighting transfer) ──
        var intermediateTexId = fboTextureId
        if (surfaceCompositor.isEnabled()) {
            surfaceCompositor.composite(fboTextureId, compositeFboId, previewWidth, previewHeight)
            intermediateTexId = compositeFboTextureId
        }

        // ── M3 Pass 2b: MaskCompositor (Person layer on top) ──
        val finalTexId: Int
        if (maskCompositor.isActive()) {
            val targetFbo = if (intermediateTexId == compositeFboTextureId) fboId else compositeFboId
            val outTexId = if (intermediateTexId == compositeFboTextureId) fboTextureId else compositeFboTextureId
            maskCompositor.composite(intermediateTexId, targetFbo, previewWidth, previewHeight)
            finalTexId = outTexId
        } else {
            finalTexId = intermediateTexId
        }

        // ── Pass 2: Blit final texture → Flutter SurfaceProducer window surface ──
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, previewWidth, previewHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(blitProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, finalTexId)
        GLES20.glUniform1i(blitSamplerHandle, 0)

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(blitPosHandle)
        GLES20.glVertexAttribPointer(blitPosHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)

        blitTexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(blitTexHandle)
        GLES20.glVertexAttribPointer(blitTexHandle, 2, GLES20.GL_FLOAT, false, 0, blitTexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(blitPosHandle)
        GLES20.glDisableVertexAttribArray(blitTexHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        // ── Pass 3: Blit final texture → MediaCodec input surface if recording ──
        if (isRecordingActive.get() && encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)
            GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(blitProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, finalTexId)
            GLES20.glUniform1i(blitSamplerHandle, 0)

            quadBuffer.position(0)
            GLES20.glEnableVertexAttribArray(blitPosHandle)
            GLES20.glVertexAttribPointer(blitPosHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)

            blitTexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(blitTexHandle)
            GLES20.glVertexAttribPointer(blitTexHandle, 2, GLES20.GL_FLOAT, false, 0, blitTexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(blitPosHandle)
            GLES20.glDisableVertexAttribArray(blitTexHandle)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

            if (recordingStartNs == 0L) {
                recordingStartNs = System.nanoTime()
            }
            val ptsNs = System.nanoTime() - recordingStartNs
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, ptsNs)
            EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)

            // Restore preview surface current
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        mainHandler.post {
            if (isRunning.get()) {
                producer.scheduleFrame()
            }
        }

        val glRenderMs = (System.nanoTime() - renderStartNs) / 1_000_000.0
        trackStats(glRenderMs)
    }

    // ── M3: Mask/background effect controls ──────────────────────────────────

    /** Enable or disable person segmentation compositing. */
    fun setMaskEnabled(enabled: Boolean) {
        glHandler.post {
            maskCompositor.setEnabled(enabled)
            if (!enabled) {
                temporalSmoother.reset()
            }
        }
    }

    /** Switch between solid background color and background blur. */
    fun setMaskUseBlur(useBlur: Boolean) {
        glHandler.post { maskCompositor.setUseBlur(useBlur) }
    }

    /** Set the solid background color (0..1 per channel). */
    fun setMaskBgColor(r: Float, g: Float, b: Float) {
        glHandler.post { maskCompositor.setBgColor(r, g, b) }
    }

    /** Set background blur radius (1..8). */
    fun setMaskBlurRadius(radius: Int) {
        glHandler.post { maskCompositor.setBlurRadius(radius) }
    }

    /** Set edge smoothness (0 = hard edge, 0.3 = very soft). */
    fun setMaskSmoothEdge(edge: Float) {
        glHandler.post { maskCompositor.setSmoothEdge(edge) }
    }

    // ── M4: Theme surfaces & crossfade controls ──────────────────────────────

    /**
     * Applies new theme surface textures (wall & floor) with optional crossfade animation.
     */
    fun applyThemeSurfaces(
        wallTexId: Int,
        floorTexId: Int,
        wallTileM: Float = 1.0f,
        floorTileM: Float = 1.0f,
        crossfadeMs: Int = 400
    ) {
        glHandler.post {
            surfaceCompositor.applyThemeSurfaces(
                newWallTexId = wallTexId,
                newFloorTexId = floorTexId,
                wallTileM = wallTileM,
                floorTileM = floorTileM,
                crossfadeMs = crossfadeMs
            )
        }
    }

    /** Updates the multi-class architectural surface segmentation mask. */
    fun updateSceneMask(maskRgba: ByteArray, width: Int, height: Int) {
        glHandler.post {
            surfaceCompositor.updateSceneMask(maskRgba, width, height)
        }
    }

    /** Enables or disables surface retexturing. */
    fun setSurfaceCompositorEnabled(enabled: Boolean) {
        glHandler.post {
            surfaceCompositor.setEnabled(enabled)
        }
    }



    private fun trackStats(glRenderMs: Double) {
        frameCount++
        totalGlRenderMs += glRenderMs
        val now = SystemClock.elapsedRealtime()
        if (lastStatsTimeMs == 0L) {
            lastStatsTimeMs = now
            return
        }
        val diffMs = now - lastStatsTimeMs
        if (diffMs >= 1000L) {
            val fps = (frameCount * 1000.0) / diffMs
            val avgGlMs = totalGlRenderMs / frameCount
            frameCount = 0
            totalGlRenderMs = 0.0
            lastStatsTimeMs = now

            if (flutterApi != null) {
                val personMl = if (maskCompositor.isEnabled()) (personSegmentor?.lastInferenceMs ?: 0.0) else 0.0
                val sceneMl = if (surfaceCompositor.isEnabled()) (sceneSegmentor?.lastInferenceMs ?: 0.0) else 0.0
                val currentMlMs = maxOf(personMl, sceneMl)
                val isEffectEnabled = maskCompositor.isEnabled() || surfaceCompositor.isEnabled()
                Log.i(TAG, "FrameStats: fps=${"%.1f".format(fps)}, glMs=${"%.1f".format(avgGlMs)}, mlMs=${"%.1f".format(currentMlMs)}, effectEnabled=$isEffectEnabled")
                val stats = FrameStats(
                    fpsCurrent = fps,
                    glRenderMs = avgGlMs,
                    mlMs = currentMlMs,
                    tier = Tier.M,
                    thermalStatus = 0L,
                    memoryPssMb = 0L,
                )
                mainHandler.post {
                    flutterApi.onStats(stats) {}
                }
            }
        }
    }

    private fun createProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("Could not link program: $info")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Could not compile shader $type: $info")
        }
        return shader
    }
}
