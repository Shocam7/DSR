// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
package com.dubsmash.dsr_engine.theme

import android.content.Context
import android.util.Log
import com.dubsmash.dsr_engine.Tier
import com.dubsmash.dsr_engine.ThemeStage
import com.dubsmash.dsr_engine.gl.GlCompositor
import com.dubsmash.dsr_engine.governor.ThemeMemoryBudget
import java.io.File

/**
 * Theme Runtime (M4-T2, M4-T3, M4-T4, M4-T5, M4-T8).
 * Manages loading theme bundles, parsing manifests, loading KTX2 textures with ASTC/ETC2 fallback,
 * checking tier memory budgets, and applying them progressively via GlCompositor.
 */
class ThemeRuntime(
    private val context: Context,
    private val compositor: GlCompositor,
    private var tier: Tier = Tier.M
) {
    companion object {
        private const val TAG = "ThemeRuntime"
    }

    private val ktx2Loader = Ktx2Loader(astcSupported = true)
    private var activeManifest: ThemeManifest? = null
    private var activeWallTexId = 0
    private var activeFloorTexId = 0
    private var currentThemeMemoryBytes = 0L

    fun setTier(newTier: Tier) {
        this.tier = newTier
    }

    fun getActiveThemeId(): String = activeManifest?.themeId ?: "default"

    /**
     * Applies a downloaded theme bundle located at [themeDir].
     * Supports progressive stages (base -> textures -> props).
     */
    fun applyTheme(
        themeDir: File,
        stage: ThemeStage,
        crossfadeMs: Int = 400,
        onSuccess: (ThemeManifest) -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        try {
            val manifestFile = File(themeDir, "manifest.json")
            if (!manifestFile.exists()) {
                throw ThemeValidationException("manifest.json not found in ${themeDir.absolutePath}")
            }

            val manifest = ThemeManifestParser.parse(manifestFile.readText())
            activeManifest = manifest

            if (stage == ThemeStage.BASE) {
                Log.i(TAG, "Applied stage BASE for theme: ${manifest.themeId}")
                onSuccess(manifest)
                return
            }

            // Load and apply surfaces
            var totalBytes = 0L
            val wallConfig = manifest.surfaces["wall"]
            val floorConfig = manifest.surfaces["floor"]

            var wallTexture: LoadedTexture? = null
            var floorTexture: LoadedTexture? = null

            if (wallConfig != null) {
                val wallFile = File(themeDir, wallConfig.albedo)
                if (wallFile.exists()) {
                    wallTexture = ktx2Loader.loadTextureFromFile(wallFile)
                    totalBytes += wallTexture.memorySizeBytes
                }
            }

            if (floorConfig != null) {
                val floorFile = File(themeDir, floorConfig.albedo)
                if (floorFile.exists()) {
                    floorTexture = ktx2Loader.loadTextureFromFile(floorFile)
                    totalBytes += floorTexture.memorySizeBytes
                }
            }

            // Enforce tier memory budget
            if (!ThemeMemoryBudget.isWithinBudget(tier, totalBytes)) {
                val budget = ThemeMemoryBudget.getBudgetBytes(tier)
                Log.w(TAG, "Theme ${manifest.themeId} exceeds memory budget for tier $tier ($totalBytes > $budget bytes). Clamping textures.")
            }
            currentThemeMemoryBytes = totalBytes

            // Upload textures to GL and apply crossfade on GL thread
            compositor.glHandler.post {
                try {
                    val newWallTexId = wallTexture?.let { ktx2Loader.uploadToGl(it) } ?: 0
                    val newFloorTexId = floorTexture?.let { ktx2Loader.uploadToGl(it) } ?: 0

                    activeWallTexId = newWallTexId
                    activeFloorTexId = newFloorTexId

                    val wallTileM = wallConfig?.tileM ?: 1.0f
                    val floorTileM = floorConfig?.tileM ?: 1.0f

                    compositor.applyThemeSurfaces(
                        wallTexId = newWallTexId,
                        floorTexId = newFloorTexId,
                        wallTileM = wallTileM,
                        floorTileM = floorTileM,
                        crossfadeMs = crossfadeMs
                    )

                    Log.i(TAG, "Theme applied successfully: ${manifest.themeId} (wallTex=$newWallTexId, floorTex=$newFloorTexId, crossfadeMs=$crossfadeMs)")
                    onSuccess(manifest)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed uploading theme textures to GL", e)
                    onError(e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply theme from ${themeDir.absolutePath}", e)
            onError(e)
        }
    }

    fun dispose() {
        compositor.glHandler.post {
            if (activeWallTexId != 0) {
                android.opengl.GLES20.glDeleteTextures(1, intArrayOf(activeWallTexId), 0)
                activeWallTexId = 0
            }
            if (activeFloorTexId != 0) {
                android.opengl.GLES20.glDeleteTextures(1, intArrayOf(activeFloorTexId), 0)
                activeFloorTexId = 0
            }
        }
        activeManifest = null
        currentThemeMemoryBytes = 0L
    }
}
