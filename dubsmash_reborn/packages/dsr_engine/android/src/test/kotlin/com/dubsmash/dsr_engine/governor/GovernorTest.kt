// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
package com.dubsmash.dsr_engine.governor

import com.dubsmash.dsr_engine.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GovernorTest {
    private class TestListener : GovernorListener {
        var lastQuality: RenderQuality? = null
        var lastStage: DegradationStage? = null
        var stageHistory = mutableListOf<DegradationStage>()
        var changesCount = 0

        override fun onQualityChanged(quality: RenderQuality) {
            lastQuality = quality
            changesCount++
        }

        override fun onDegradationChanged(stage: DegradationStage) {
            lastStage = stage
            stageHistory.add(stage)
        }
    }

    private lateinit var listener: TestListener
    private lateinit var governor: Governor

    @Before
    fun setup() {
        listener = TestListener()
        governor = Governor(listener)
    }

    @Test
    fun thermalCriticalForcesTierDowngrade() {
        governor.feed(GovernorInput(20.0, 4))
        assertEquals(DegradationStage.TIER_DOWNGRADE, governor.getDegradationStage())
        assertEquals(RenderQuality.MINIMAL, governor.getQuality())
        assertFalse(governor.isDepthEnabled())
        assertFalse(governor.isPropsEnabled())
        assertFalse(governor.isParticlesEnabled())
        assertFalse(governor.isBaseTierRetained())
    }

    @Test
    fun thermalModerateCapsAtDropDepth() {
        governor.feed(GovernorInput(20.0, 2))
        assertEquals(DegradationStage.DROP_DEPTH, governor.getDegradationStage())
        assertFalse(governor.isDepthEnabled())
        assertTrue(governor.isPropsEnabled())
    }

    /**
     * M4-A6: Forced-load test: governor steps down in documented order, then recovers with hysteresis.
     * Order: depth -> props -> mask res/cadence -> particles -> tier
     */
    @Test
    fun forcedLoadStepsDownInDocumentedOrderThenRecovers_M4_A6() {
        assertEquals(DegradationStage.FULL_QUALITY, governor.getDegradationStage())
        assertTrue(governor.isDepthEnabled())
        assertTrue(governor.isPropsEnabled())
        assertTrue(governor.isMaskFullRes())
        assertTrue(governor.isParticlesEnabled())
        assertTrue(governor.isBaseTierRetained())

        // Step 1: 5 slow frames -> DROP_DEPTH
        for (i in 1..5) governor.feed(GovernorInput(50.0, 0))
        assertEquals(DegradationStage.DROP_DEPTH, governor.getDegradationStage())
        assertFalse(governor.isDepthEnabled())
        assertTrue(governor.isPropsEnabled())

        // Step 2: 5 more slow frames -> DROP_PROPS
        for (i in 1..5) governor.feed(GovernorInput(50.0, 0))
        assertEquals(DegradationStage.DROP_PROPS, governor.getDegradationStage())
        assertFalse(governor.isPropsEnabled())
        assertTrue(governor.isMaskFullRes())

        // Step 3: 5 more slow frames -> REDUCE_MASK
        for (i in 1..5) governor.feed(GovernorInput(50.0, 0))
        assertEquals(DegradationStage.REDUCE_MASK, governor.getDegradationStage())
        assertFalse(governor.isMaskFullRes())
        assertTrue(governor.isParticlesEnabled())

        // Step 4: 5 more slow frames -> DROP_PARTICLES
        for (i in 1..5) governor.feed(GovernorInput(50.0, 0))
        assertEquals(DegradationStage.DROP_PARTICLES, governor.getDegradationStage())
        assertFalse(governor.isParticlesEnabled())
        assertTrue(governor.isBaseTierRetained())

        // Step 5: 5 more slow frames -> TIER_DOWNGRADE
        for (i in 1..5) governor.feed(GovernorInput(50.0, 0))
        assertEquals(DegradationStage.TIER_DOWNGRADE, governor.getDegradationStage())
        assertFalse(governor.isBaseTierRetained())

        // Now test recovery: 30 consecutive fast frames needed per stage
        // Recovery Step 1 -> DROP_PARTICLES
        for (i in 1..30) governor.feed(GovernorInput(20.0, 0))
        assertEquals(DegradationStage.DROP_PARTICLES, governor.getDegradationStage())

        // Recovery Step 2 -> REDUCE_MASK
        for (i in 1..30) governor.feed(GovernorInput(20.0, 0))
        assertEquals(DegradationStage.REDUCE_MASK, governor.getDegradationStage())

        // Recovery Step 3 -> DROP_PROPS
        for (i in 1..30) governor.feed(GovernorInput(20.0, 0))
        assertEquals(DegradationStage.DROP_PROPS, governor.getDegradationStage())

        // Recovery Step 4 -> DROP_DEPTH
        for (i in 1..30) governor.feed(GovernorInput(20.0, 0))
        assertEquals(DegradationStage.DROP_DEPTH, governor.getDegradationStage())

        // Recovery Step 5 -> FULL_QUALITY
        for (i in 1..30) governor.feed(GovernorInput(20.0, 0))
        assertEquals(DegradationStage.FULL_QUALITY, governor.getDegradationStage())
        assertTrue(governor.isDepthEnabled())
        assertTrue(governor.isPropsEnabled())
        assertTrue(governor.isMaskFullRes())
        assertTrue(governor.isParticlesEnabled())
        assertTrue(governor.isBaseTierRetained())
    }

    /**
     * M4-A5: Loaded theme memory <= tier budget (8 MB L, 16 MB M, 25 MB H)
     */
    @Test
    fun themeMemoryBudgetEnforcement_M4_A5() {
        assertEquals(8L * 1024 * 1024, ThemeMemoryBudget.getBudgetBytes(Tier.L))
        assertEquals(16L * 1024 * 1024, ThemeMemoryBudget.getBudgetBytes(Tier.M))
        assertEquals(25L * 1024 * 1024, ThemeMemoryBudget.getBudgetBytes(Tier.H))

        // Valid allocations
        assertTrue(ThemeMemoryBudget.isWithinBudget(Tier.L, 7L * 1024 * 1024))
        assertTrue(ThemeMemoryBudget.isWithinBudget(Tier.M, 15L * 1024 * 1024))
        assertTrue(ThemeMemoryBudget.isWithinBudget(Tier.H, 24L * 1024 * 1024))

        // Over-budget allocations
        assertFalse(ThemeMemoryBudget.isWithinBudget(Tier.L, 9L * 1024 * 1024))
        assertFalse(ThemeMemoryBudget.isWithinBudget(Tier.M, 17L * 1024 * 1024))
        assertFalse(ThemeMemoryBudget.isWithinBudget(Tier.H, 26L * 1024 * 1024))
    }

    @Test
    fun hysteresisPreventsFastOscillation() {
        val initialStage = governor.getDegradationStage()
        assertEquals(DegradationStage.FULL_QUALITY, initialStage)

        for (i in 1..40) {
            if (i % 2 == 0) {
                governor.feed(GovernorInput(50.0, 0))
            } else {
                governor.feed(GovernorInput(20.0, 0))
            }
        }
        assertEquals(DegradationStage.FULL_QUALITY, governor.getDegradationStage())
        assertEquals(0, listener.changesCount)
    }
}
