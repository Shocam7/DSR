// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
package com.dubsmash.dsr_engine.governor

import com.dubsmash.dsr_engine.Tier
import java.util.ArrayDeque

enum class RenderQuality {
    FULL,
    REDUCED,
    MINIMAL
}

/**
 * M4 Degradation Order:
 * depth -> props -> mask res/cadence -> particles -> tier
 */
enum class DegradationStage(val level: Int) {
    FULL_QUALITY(0),
    DROP_DEPTH(1),
    DROP_PROPS(2),
    REDUCE_MASK(3),
    DROP_PARTICLES(4),
    TIER_DOWNGRADE(5);

    fun next(): DegradationStage {
        return when (this) {
            FULL_QUALITY -> DROP_DEPTH
            DROP_DEPTH -> DROP_PROPS
            DROP_PROPS -> REDUCE_MASK
            REDUCE_MASK -> DROP_PARTICLES
            DROP_PARTICLES -> TIER_DOWNGRADE
            TIER_DOWNGRADE -> TIER_DOWNGRADE
        }
    }

    fun prev(): DegradationStage {
        return when (this) {
            TIER_DOWNGRADE -> DROP_PARTICLES
            DROP_PARTICLES -> REDUCE_MASK
            REDUCE_MASK -> DROP_PROPS
            DROP_PROPS -> DROP_DEPTH
            DROP_DEPTH -> FULL_QUALITY
            FULL_QUALITY -> FULL_QUALITY
        }
    }
}

object ThemeMemoryBudget {
    const val TIER_L_BUDGET_BYTES = 8L * 1024 * 1024
    const val TIER_M_BUDGET_BYTES = 16L * 1024 * 1024
    const val TIER_H_BUDGET_BYTES = 25L * 1024 * 1024

    fun getBudgetBytes(tier: Tier): Long = when (tier) {
        Tier.L -> TIER_L_BUDGET_BYTES
        Tier.M -> TIER_M_BUDGET_BYTES
        Tier.H -> TIER_H_BUDGET_BYTES
    }

    fun isWithinBudget(tier: Tier, currentBytes: Long): Boolean {
        return currentBytes <= getBudgetBytes(tier)
    }
}

data class GovernorInput(val frameTimeMs: Double, val thermalStatus: Int)

interface GovernorListener {
    fun onQualityChanged(quality: RenderQuality) {}
    fun onDegradationChanged(stage: DegradationStage) {}
}

class Governor(private val listener: GovernorListener) {
    private var currentQuality = RenderQuality.FULL
    private var currentStage = DegradationStage.FULL_QUALITY

    private val frameTimes = ArrayDeque<Double>()
    private val maxWindowSize = 30
    private var consecutiveSlowFrames = 0
    private var consecutiveFastFrames = 0

    companion object {
        const val SLOW_FRAME_THRESHOLD_MS = 40.0
        const val FAST_FRAME_THRESHOLD_MS = 28.0
        const val DOWNGRADE_TRIGGER = 5
        const val UPGRADE_TRIGGER = 30
    }

    fun feed(input: GovernorInput) {
        frameTimes.addLast(input.frameTimeMs)
        if (frameTimes.size > maxWindowSize) {
            frameTimes.removeFirst()
        }

        val currentFrameTime = input.frameTimeMs
        var targetStage = currentStage

        if (currentFrameTime >= SLOW_FRAME_THRESHOLD_MS) {
            consecutiveSlowFrames++
            consecutiveFastFrames = 0
        } else if (currentFrameTime <= FAST_FRAME_THRESHOLD_MS) {
            consecutiveFastFrames++
            consecutiveSlowFrames = 0
        } else {
            consecutiveSlowFrames = 0
            consecutiveFastFrames = 0
        }

        if (consecutiveSlowFrames >= DOWNGRADE_TRIGGER) {
            targetStage = targetStage.next()
            consecutiveSlowFrames = 0
        }

        if (consecutiveFastFrames >= UPGRADE_TRIGGER) {
            targetStage = targetStage.prev()
            consecutiveFastFrames = 0
        }

        // Thermal overrides
        if (input.thermalStatus >= 4) {
            targetStage = DegradationStage.TIER_DOWNGRADE
        } else if (input.thermalStatus == 3) {
            if (targetStage.level < DegradationStage.REDUCE_MASK.level) {
                targetStage = DegradationStage.REDUCE_MASK
            }
        } else if (input.thermalStatus == 2) {
            if (targetStage.level < DegradationStage.DROP_DEPTH.level) {
                targetStage = DegradationStage.DROP_DEPTH
            }
        }

        if (targetStage != currentStage) {
            currentStage = targetStage
            // Map stage to legacy RenderQuality for backward compatibility
            currentQuality = when (currentStage) {
                DegradationStage.FULL_QUALITY, DegradationStage.DROP_DEPTH -> RenderQuality.FULL
                DegradationStage.DROP_PROPS, DegradationStage.REDUCE_MASK -> RenderQuality.REDUCED
                DegradationStage.DROP_PARTICLES, DegradationStage.TIER_DOWNGRADE -> RenderQuality.MINIMAL
            }
            listener.onDegradationChanged(currentStage)
            listener.onQualityChanged(currentQuality)
        }
    }

    fun getQuality(): RenderQuality = currentQuality

    fun getDegradationStage(): DegradationStage = currentStage

    fun isDepthEnabled(): Boolean = currentStage.level < DegradationStage.DROP_DEPTH.level
    fun isPropsEnabled(): Boolean = currentStage.level < DegradationStage.DROP_PROPS.level
    fun isMaskFullRes(): Boolean = currentStage.level < DegradationStage.REDUCE_MASK.level
    fun isParticlesEnabled(): Boolean = currentStage.level < DegradationStage.DROP_PARTICLES.level
    fun isBaseTierRetained(): Boolean = currentStage.level < DegradationStage.TIER_DOWNGRADE.level

    fun reset() {
        currentQuality = RenderQuality.FULL
        currentStage = DegradationStage.FULL_QUALITY
        frameTimes.clear()
        consecutiveSlowFrames = 0
        consecutiveFastFrames = 0
    }
}
