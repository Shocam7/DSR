// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// EngineHostApiImpl — implements the Pigeon EngineHostApi.
//
// Owner thread: main thread (Pigeon dispatches here).
// All async work (GL, ML, Camera) is delegated to EngineSession.

package com.dubsmash.dsr_engine

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.view.TextureRegistry
import java.io.File
import java.io.FileInputStream

/**
 * Pigeon [EngineHostApi] implementation.
 *
 * Creates and delegates to [EngineSession] objects, keyed by [sessionId].
 */
internal class EngineHostApiImpl(
    private val context: Context,
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

        val session = EngineSession(
            sessionId = sessionId,
            context = context,
            producer = producer,
            flutterApi = flutterApi,
            initialIsFrontCamera = config.isFrontCamera,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
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
        val session = sessions[sessionId.toInt()]
        if (session == null) {
            callback(Result.failure(IllegalStateException("No session $sessionId")))
            return
        }
        session.startPreview()
        callback(Result.success(Unit))
    }

    override fun stopPreview(sessionId: Long) {
        sessions[sessionId.toInt()]?.stopPreview()
    }

    override fun switchCamera(sessionId: Long, callback: (Result<Unit>) -> Unit) {
        val session = sessions[sessionId.toInt()]
        if (session == null) {
            callback(Result.failure(IllegalStateException("No session $sessionId")))
            return
        }
        session.switchCamera(callback)
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

    override fun setBackgroundEffect(
        sessionId: Long,
        enabled: Boolean,
        useBlur: Boolean,
        colorArgb: Long,
        blurRadius: Long,
    ) {
        sessions[sessionId.toInt()]?.setBackgroundEffect(
            enabled, useBlur, colorArgb.toInt(), blurRadius.toInt(),
        )
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

    override fun exportToGallery(filePath: String, callback: (Result<String>) -> Unit) {
        try {
            val srcFile = File(filePath)
            if (!srcFile.exists()) {
                callback(Result.failure(IllegalArgumentException("Source file does not exist: $filePath")))
                return
            }

            val filename = "DSR_${System.currentTimeMillis()}.mp4"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Dubsmash")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    callback(Result.failure(IllegalStateException("Failed to create MediaStore entry")))
                    return
                }

                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(srcFile).use { input ->
                        input.copyTo(out)
                    }
                }

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                Log.i(TAG, "Video exported to gallery via MediaStore: $uri")
                callback(Result.success(uri.toString()))
            } else {
                val dcimDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Dubsmash")
                if (!dcimDir.exists()) dcimDir.mkdirs()
                val destFile = File(dcimDir, filename)
                srcFile.copyTo(destFile, overwrite = true)
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf("video/mp4")
                ) { path, uri ->
                    Log.i(TAG, "Scanned $path -> $uri")
                }
                callback(Result.success(destFile.absolutePath))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export video to gallery", e)
            callback(Result.failure(e))
        }
    }

    override fun disposeSession(sessionId: Long) {
        sessions.remove(sessionId.toInt())?.dispose()
    }

    companion object {
        private const val TAG = "DsrEngineHostApi"
    }
}
