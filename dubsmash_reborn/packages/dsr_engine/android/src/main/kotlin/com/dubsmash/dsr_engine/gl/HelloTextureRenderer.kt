// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// HelloTextureRenderer — M0 gate: proves the EGL ↔ Flutter Texture pipe.
//
// Owner thread: main thread for start/stop/dispose.
//              GL render thread for all OpenGL calls.
//
// What it does:
//   - Creates an EGL context on a dedicated HandlerThread.
//   - Creates a window surface from the SurfaceProducer's surface.
//   - Animates a HSV colour across hue at ~30 fps.
//   - Survives surface recreation (app background / config change) by only
//     recreating the EGL window surface, not the whole context.
//   - Calls producer.scheduleFrame() after each frame to notify Flutter compositor.
//   - Reports FrameStats via EngineFlutterApi at 1 Hz for the debug HUD.
//
// Gate criterion (from Engineering Guide §M0):
//   Runs at 30+ fps; survives rotate, background/foreground, and 20 session
//   create/dispose cycles with no native memory growth.

package com.dubsmash.dsr_engine.gl

import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.dubsmash.dsr_engine.EngineFlutterApi
import com.dubsmash.dsr_engine.FrameStats
import com.dubsmash.dsr_engine.Tier
import io.flutter.view.TextureRegistry

/**
 * Renders an animated colour gradient into a [TextureRegistry.SurfaceProducer].
 *
 * This class is intentionally minimal — it exists only to validate the
 * EGL context → Flutter Texture path. It will be replaced by the full
 * CameraX + GL compositor in M1.
 */
internal class HelloTextureRenderer(
    private val producer: TextureRegistry.SurfaceProducer,
    private val flutterApi: EngineFlutterApi? = null,
) : TextureRegistry.SurfaceProducer.Callback {

    // ── Threading ─────────────────────────────────────────────────────────────

    /** Dedicated GL render thread. All EGL calls happen here. */
    private val glThread = HandlerThread("dsr-gl").also { it.start() }
    private val glHandler = Handler(glThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── EGL state ─────────────────────────────────────────────────────────────

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // ── Animation state ───────────────────────────────────────────────────────

    private var hue = 0f
    @Volatile
    private var isRunning = false

    // Stats tracking for HUD (sent at 1 Hz)
    private var frameCount = 0
    private var lastStatsTimeMs = 0L
    private var totalGlRenderMs = 0.0

    private val renderRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            renderFrame()
            glHandler.postDelayed(this, 33L) // Target ~30 fps
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    init {
        producer.setCallback(this)
        glHandler.post {
            initEgl()
            val surface = producer.surface
            if (surface != null && surface.isValid) {
                createWindowSurface(surface)
            }
        }
    }

    /** Start the render loop. Safe to call from any thread. */
    fun start() {
        if (isRunning) return
        isRunning = true
        glHandler.post {
            val surface = producer.surface
            if (eglSurface == EGL14.EGL_NO_SURFACE && surface != null && surface.isValid) {
                createWindowSurface(surface)
            }
            glHandler.removeCallbacks(renderRunnable)
            glHandler.post(renderRunnable)
        }
    }

    /** Pause the render loop without destroying EGL state. */
    fun stop() {
        isRunning = false
        glHandler.removeCallbacks(renderRunnable)
        mainHandler.removeCallbacksAndMessages(null)
    }

    /** Release all EGL and GL resources. Must be called to avoid leaks. */
    fun dispose() {
        isRunning = false
        glHandler.removeCallbacks(renderRunnable)
        mainHandler.removeCallbacksAndMessages(null)
        glHandler.post {
            destroyWindowSurface()
            destroyEglContext()
        }
        glThread.quitSafely()
    }

    // ── SurfaceProducer.Callback ──────────────────────────────────────────────

    override fun onSurfaceCreated() {
        glHandler.post {
            val surface = producer.surface
            if (surface != null && surface.isValid) {
                createWindowSurface(surface)
            }
        }
    }

    override fun onSurfaceAvailable() {
        onSurfaceCreated()
    }

    override fun onSurfaceDestroyed() {
        glHandler.post { destroyWindowSurface() }
    }

    override fun onSurfaceCleanup() {
        onSurfaceDestroyed()
    }

    // ── GL work (called only on glThread) ────────────────────────────────────

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Failed to get EGL display" }

        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
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

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Failed to create EGL context" }
    }

    private fun createWindowSurface(surface: Surface) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) return
        if (!surface.isValid) return
        destroyWindowSurface()

        val attribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, getEglConfig(), surface, attribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            return
        }
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun getEglConfig(): EGLConfig {
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
        return configs[0]!!
    }

    private fun destroyWindowSurface() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun destroyEglContext() {
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun renderFrame() {
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            val surface = producer.surface
            if (surface != null && surface.isValid) {
                createWindowSurface(surface)
            }
            if (eglSurface == EGL14.EGL_NO_SURFACE) return
        }

        val renderStartNs = System.nanoTime()

        // Advance hue (full cycle in ~5 s at 30 fps).
        hue = (hue + 2f) % 360f
        val rgb = Color.HSVToColor(floatArrayOf(hue, 0.8f, 0.6f))

        GLES20.glClearColor(
            Color.red(rgb) / 255f,
            Color.green(rgb) / 255f,
            Color.blue(rgb) / 255f,
            1f,
        )
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        // Inform Flutter that a new frame has been rendered and is ready to composite.
        // SurfaceProducer.scheduleFrame() requires execution on the main (UI) thread.
        mainHandler.post {
            if (isRunning) {
                producer.scheduleFrame()
            }
        }

        val glRenderMs = (System.nanoTime() - renderStartNs) / 1_000_000.0
        trackStats(glRenderMs)
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
                val stats = FrameStats(
                    fpsCurrent = fps,
                    glRenderMs = avgGlMs,
                    mlMs = 0.0,
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
}
