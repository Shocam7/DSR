// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// PluginLifecycleOwner — custom LifecycleOwner for CameraX managed by dsr_engine.
// Driven by Activity callbacks and preview session states.

package com.dubsmash.dsr_engine.camera

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Custom [LifecycleOwner] that allows dsr_engine to start, pause, resume,
 * and stop CameraX independently or in synchronization with the host Activity.
 *
 * All state mutations are dispatched to the main thread to ensure thread safety
 * for [LifecycleRegistry].
 */
class PluginLifecycleOwner : LifecycleOwner {

    private val mainHandler: Handler? = try {
        Handler(Looper.getMainLooper())
    } catch (_: Exception) {
        null
    }
    private val lifecycleRegistry = LifecycleRegistry(this)

    init {
        runOnMain {
            lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        }
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private fun runOnMain(block: () -> Unit) {
        val handler = mainHandler
        if (handler == null) {
            block()
            return
        }
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                block()
            } else {
                handler.post(block)
            }
        } catch (_: Exception) {
            block()
        }
    }

    fun start() {
        runOnMain {
            if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
                lifecycleRegistry.currentState = Lifecycle.State.CREATED
            }
            if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.currentState = Lifecycle.State.STARTED
            }
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun pause() {
        runOnMain {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                lifecycleRegistry.currentState = Lifecycle.State.STARTED
            }
        }
    }

    fun stop() {
        runOnMain {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.currentState = Lifecycle.State.CREATED
            }
        }
    }

    fun destroy() {
        runOnMain {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
