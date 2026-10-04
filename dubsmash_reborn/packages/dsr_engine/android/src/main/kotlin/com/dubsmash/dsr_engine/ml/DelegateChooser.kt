// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// DelegateChooser — picks the fastest working MediaPipe delegate for the segmenter.
// Order: GPU → CPU. (NNAPI is not exposed by MediaPipe Tasks; kept in the enum for
// API stability and tests.) A failing delegate (exception, missing class, timeout)
// falls through to the next. The choice and latency are cached in SharedPreferences
// per device model + Android version for 7 days.
// Requires: implementation 'com.google.mediapipe:tasks-vision:0.10.14'
package com.dubsmash.dsr_engine.ml

import android.content.Context
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer

enum class DelegateType { NNAPI, GPU, CPU }

data class DelegateResult(
    val delegateType: DelegateType,
    val latencyMs: Double,
    val segmentor: PersonSegmentor,
)

object DelegateChooser {
    private const val TAG = "DelegateChooser"
    private const val PREF_NAME = "dsr_delegate_cache"
    private const val CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L

    /** Force CPU delegate for rock-solid stability and zero OpenCL/GPU driver aborts. */
    private val ORDER = listOf(DelegateType.CPU)

    /**
     * Must be called off the main/GL threads (it blocks while the segmenter warms up).
     * Throws IllegalStateException if every delegate fails.
     */
    fun chooseDelegate(context: Context, modelBuffer: ByteBuffer): DelegateResult {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val key = "${Build.MODEL}_${Build.VERSION.SDK_INT}"
        val now = System.currentTimeMillis()

        // Crash sentinel check: if previous launch crashed while probing GPU, blacklist GPU
        val gpuProbePending = prefs.getBoolean("${key}_gpu_probe_pending", false)
        val cachedType = prefs.getString("${key}_type", null)
        val fresh = now - prefs.getLong("${key}_time", 0) < CACHE_TTL_MS

        val candidates = if (gpuProbePending || (fresh && cachedType == DelegateType.CPU.name)) {
            Log.w(TAG, "GPU delegate previously crashed or blacklisted, falling back to CPU")
            listOf(DelegateType.CPU)
        } else {
            ORDER
        }

        for (type in candidates) {
            if (type == DelegateType.GPU) {
                // Set synchronous sentinel before probing GPU in case native abort crashes process
                prefs.edit().putBoolean("${key}_gpu_probe_pending", true).commit()
            }
            val segmentor = PersonSegmentor(context, modelBuffer, type)
            try {
                val latency = segmentor.initialize()
                Log.i(TAG, "Delegate $type OK, ${"%.1f".format(latency)} ms/frame")
                prefs.edit()
                    .putBoolean("${key}_gpu_probe_pending", false)
                    .putLong("${key}_time", now)
                    .putString("${key}_type", type.name)
                    .putFloat("${key}_latency", latency.toFloat())
                    .commit()
                return DelegateResult(type, latency, segmentor)
            } catch (t: Throwable) {
                Log.w(TAG, "Delegate $type failed, trying next", t)
                if (type == DelegateType.GPU) {
                    prefs.edit().putBoolean("${key}_gpu_probe_pending", false).commit()
                }
                segmentor.dispose()
            }
        }
        throw IllegalStateException("All delegates failed, including CPU fallback")
    }
}
