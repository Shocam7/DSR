// Copyright (c) 2026 DubSmash Reborn. All rights reserved.

package com.dubsmash.dsr_engine.camera

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.Lifecycle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PluginLifecycleOwnerTest {

    @BeforeEach
    fun setUp() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) {
                runnable.run()
            }

            override fun postToMainThread(runnable: Runnable) {
                runnable.run()
            }

            override fun isMainThread(): Boolean = true
        })
    }

    @AfterEach
    fun tearDown() {
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun testLifecycleTransitions() {
        val owner = PluginLifecycleOwner()
        assertEquals(Lifecycle.State.INITIALIZED, owner.lifecycle.currentState)

        owner.start()
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)

        owner.pause()
        assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)

        owner.stop()
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)

        owner.start()
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)

        owner.destroy()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    @Test
    fun testIdempotentLifecycleTransitions() {
        val owner = PluginLifecycleOwner()
        owner.start()
        owner.start()
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)

        owner.destroy()
        owner.destroy()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }
}
