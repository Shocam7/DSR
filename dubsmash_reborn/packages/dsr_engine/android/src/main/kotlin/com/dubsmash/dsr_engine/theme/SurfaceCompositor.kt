// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
package com.dubsmash.dsr_engine.theme

import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Surface tiling and real-time lighting transfer compositor (M4-T4 & M4-T5).
 * Retextures segmented architectural surfaces (wall, floor, ceiling) with KTX2 textures,
 * dynamically preserves physical scene shadows and gradients via lum(frame) / mean_lum(region),
 * and executes temporal crossfades between themes without stutter.
 */
class SurfaceCompositor {
    companion object {
        private const val TAG = "SurfaceCompositor"

        private const val VERTEX_SHADER_SURFACE = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER_SURFACE = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sCamera;
            uniform sampler2D sSceneMask;
            uniform sampler2D sWallTexture;
            uniform sampler2D sFloorTexture;
            uniform sampler2D sPrevWallTexture;
            uniform sampler2D sPrevFloorTexture;

            uniform vec2 uWallTile;
            uniform vec2 uFloorTile;
            uniform float uCrossfadeAlpha;
            uniform float uMeanLumWall;
            uniform float uMeanLumFloor;
            uniform int uHasWall;
            uniform int uHasFloor;
            uniform int uIsCrossfading;

            void main() {
                vec4 cameraColor = texture2D(sCamera, vTexCoord);
                vec4 mask = texture2D(sSceneMask, vTexCoord);
                float wallMask = mask.r;
                float floorMask = mask.g;

                // Frame luminance
                float lum = dot(cameraColor.rgb, vec3(0.299, 0.587, 0.114));
                vec4 resultColor = cameraColor;

                // 1. Retexture Wall with lighting transfer
                if (uHasWall > 0 && wallMask > 0.02) {
                    vec2 wallUv = vTexCoord * uWallTile;
                    vec4 wallTex = texture2D(sWallTexture, fract(wallUv));
                    if (uIsCrossfading > 0) {
                        vec4 prevWallTex = texture2D(sPrevWallTexture, fract(wallUv));
                        wallTex = mix(prevWallTex, wallTex, uCrossfadeAlpha);
                    }
                    // Engineering Guide §4.3 formula: lum(frame) / mean_lum(region)
                    float lightTransfer = clamp(lum / max(uMeanLumWall, 0.08), 0.15, 2.5);
                    vec3 shadedWall = wallTex.rgb * lightTransfer;
                    resultColor = mix(resultColor, vec4(shadedWall, 1.0), wallMask);
                }

                // 2. Retexture Floor with lighting transfer
                if (uHasFloor > 0 && floorMask > 0.02) {
                    vec2 floorUv = vTexCoord * uFloorTile;
                    vec4 floorTex = texture2D(sFloorTexture, fract(floorUv));
                    if (uIsCrossfading > 0) {
                        vec4 prevFloorTex = texture2D(sPrevFloorTexture, fract(floorUv));
                        floorTex = mix(prevFloorTex, floorTex, uCrossfadeAlpha);
                    }
                    float lightTransfer = clamp(lum / max(uMeanLumFloor, 0.08), 0.15, 2.5);
                    vec3 shadedFloor = floorTex.rgb * lightTransfer;
                    resultColor = mix(resultColor, vec4(shadedFloor, 1.0), floorMask);
                }

                gl_FragColor = resultColor;
            }
        """
    }

    private var program = 0
    private var posHandle = 0
    private var texHandle = 0
    private var cameraHandle = 0
    private var maskHandle = 0
    private var wallTexHandle = 0
    private var floorTexHandle = 0
    private var prevWallTexHandle = 0
    private var prevFloorTexHandle = 0
    private var wallTileHandle = 0
    private var floorTileHandle = 0
    private var crossfadeAlphaHandle = 0
    private var meanLumWallHandle = 0
    private var meanLumFloorHandle = 0
    private var hasWallHandle = 0
    private var hasFloorHandle = 0
    private var isCrossfadingHandle = 0

    private var quadBuf: FloatBuffer? = null
    private var texBuf: FloatBuffer? = null

    // Texture IDs
    private var activeWallTexId = 0
    private var activeFloorTexId = 0
    private var prevWallTexId = 0
    private var prevFloorTexId = 0
    private var maskTexId = 0

    // Tiling configurations
    private var wallTileU = 2.0f
    private var wallTileV = 2.0f
    private var floorTileU = 2.0f
    private var floorTileV = 2.0f

    // Lighting transfer
    var meanLumWall = 0.45f
    var meanLumFloor = 0.40f

    // Crossfade state
    private var crossfadeStartMs = 0L
    private var crossfadeDurationMs = 400L
    private var isCrossfading = false

    private var isEnabled = false

    fun init(quadBuffer: FloatBuffer, texBuffer: FloatBuffer) {
        quadBuf = quadBuffer
        texBuf = texBuffer

        program = createProgram(VERTEX_SHADER_SURFACE, FRAGMENT_SHADER_SURFACE)
        posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        cameraHandle = GLES20.glGetUniformLocation(program, "sCamera")
        maskHandle = GLES20.glGetUniformLocation(program, "sSceneMask")
        wallTexHandle = GLES20.glGetUniformLocation(program, "sWallTexture")
        floorTexHandle = GLES20.glGetUniformLocation(program, "sFloorTexture")
        prevWallTexHandle = GLES20.glGetUniformLocation(program, "sPrevWallTexture")
        prevFloorTexHandle = GLES20.glGetUniformLocation(program, "sPrevFloorTexture")

        wallTileHandle = GLES20.glGetUniformLocation(program, "uWallTile")
        floorTileHandle = GLES20.glGetUniformLocation(program, "uFloorTile")
        crossfadeAlphaHandle = GLES20.glGetUniformLocation(program, "uCrossfadeAlpha")
        meanLumWallHandle = GLES20.glGetUniformLocation(program, "uMeanLumWall")
        meanLumFloorHandle = GLES20.glGetUniformLocation(program, "uMeanLumFloor")
        hasWallHandle = GLES20.glGetUniformLocation(program, "uHasWall")
        hasFloorHandle = GLES20.glGetUniformLocation(program, "uHasFloor")
        isCrossfadingHandle = GLES20.glGetUniformLocation(program, "uIsCrossfading")

        // Allocate default 1x1 black mask texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        maskTexId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val emptyBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        emptyBuf.put(byteArrayOf(0, 0, 0, 0))
        emptyBuf.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, emptyBuf
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    fun isEnabled(): Boolean = isEnabled && (activeWallTexId != 0 || activeFloorTexId != 0)

    private var maskBuffer: ByteBuffer? = null

    /**
     * Updates the multi-class scene mask (RGBA: R=wall, G=floor, B=ceiling).
     */
    fun updateSceneMask(maskRgba: ByteArray, width: Int, height: Int) {
        if (maskTexId == 0 || maskRgba.isEmpty()) return
        var buf = maskBuffer
        if (buf == null || buf.capacity() < maskRgba.size) {
            buf = ByteBuffer.allocateDirect(maskRgba.size).order(ByteOrder.nativeOrder())
            maskBuffer = buf
        }
        buf.clear()
        buf.put(maskRgba)
        buf.position(0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * Applies new surface textures with a smooth crossfade animation.
     */
    fun applyThemeSurfaces(
        newWallTexId: Int,
        newFloorTexId: Int,
        wallTileM: Float = 1.0f,
        floorTileM: Float = 1.0f,
        crossfadeMs: Int = 400
    ) {
        if (activeWallTexId != 0 || activeFloorTexId != 0) {
            // Setup crossfade from existing textures
            prevWallTexId = activeWallTexId
            prevFloorTexId = activeFloorTexId
            isCrossfading = true
            crossfadeStartMs = SystemClock.elapsedRealtime()
            crossfadeDurationMs = crossfadeMs.toLong().coerceAtLeast(100L)
        } else {
            isCrossfading = false
        }

        activeWallTexId = newWallTexId
        activeFloorTexId = newFloorTexId
        wallTileU = (1.0f / wallTileM).coerceIn(0.1f, 10.0f) * 2.0f
        wallTileV = (1.0f / wallTileM).coerceIn(0.1f, 10.0f) * 2.0f
        floorTileU = (1.0f / floorTileM).coerceIn(0.1f, 10.0f) * 2.0f
        floorTileV = (1.0f / floorTileM).coerceIn(0.1f, 10.0f) * 2.0f
        isEnabled = true
    }

    /**
     * Executes the surface composite render pass into target FBO.
     */
    fun composite(cameraTexId: Int, targetFbo: Int, width: Int, height: Int) {
        if (!isEnabled()) return

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo)
        GLES20.glViewport(0, 0, width, height)

        GLES20.glUseProgram(program)

        // Compute crossfade alpha
        var alpha = 1.0f
        if (isCrossfading) {
            val elapsed = SystemClock.elapsedRealtime() - crossfadeStartMs
            alpha = (elapsed.toFloat() / crossfadeDurationMs.toFloat()).coerceIn(0.0f, 1.0f)
            if (alpha >= 1.0f) {
                isCrossfading = false
                // Clean up previous textures
                if (prevWallTexId != 0 && prevWallTexId != activeWallTexId) {
                    GLES20.glDeleteTextures(1, intArrayOf(prevWallTexId), 0)
                    prevWallTexId = 0
                }
                if (prevFloorTexId != 0 && prevFloorTexId != activeFloorTexId) {
                    GLES20.glDeleteTextures(1, intArrayOf(prevFloorTexId), 0)
                    prevFloorTexId = 0
                }
            }
        }

        // Bind Camera (Unit 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cameraTexId)
        GLES20.glUniform1i(cameraHandle, 0)

        // Bind Mask (Unit 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexId)
        GLES20.glUniform1i(maskHandle, 1)

        // Bind Active Wall (Unit 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, activeWallTexId)
        GLES20.glUniform1i(wallTexHandle, 2)

        // Bind Active Floor (Unit 3)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, activeFloorTexId)
        GLES20.glUniform1i(floorTexHandle, 3)

        // Bind Previous Wall (Unit 4)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (prevWallTexId != 0) prevWallTexId else activeWallTexId)
        GLES20.glUniform1i(prevWallTexHandle, 4)

        // Bind Previous Floor (Unit 5)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE5)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (prevFloorTexId != 0) prevFloorTexId else activeFloorTexId)
        GLES20.glUniform1i(prevFloorTexHandle, 5)

        // Uniforms
        GLES20.glUniform2f(wallTileHandle, wallTileU, wallTileV)
        GLES20.glUniform2f(floorTileHandle, floorTileU, floorTileV)
        GLES20.glUniform1f(crossfadeAlphaHandle, alpha)
        GLES20.glUniform1f(meanLumWallHandle, meanLumWall)
        GLES20.glUniform1f(meanLumFloorHandle, meanLumFloor)
        GLES20.glUniform1i(hasWallHandle, if (activeWallTexId != 0) 1 else 0)
        GLES20.glUniform1i(hasFloorHandle, if (activeFloorTexId != 0) 1 else 0)
        GLES20.glUniform1i(isCrossfadingHandle, if (isCrossfading) 1 else 0)

        // Vertices
        val qb = quadBuf ?: return
        val tb = texBuf ?: return
        qb.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, qb)

        tb.position(0)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, tb)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun isCrossfadeActive(): Boolean = isCrossfading

    fun getCrossfadeProgress(): Float {
        if (!isCrossfading) return 1.0f
        val elapsed = SystemClock.elapsedRealtime() - crossfadeStartMs
        return (elapsed.toFloat() / crossfadeDurationMs.toFloat()).coerceIn(0.0f, 1.0f)
    }

    fun dispose() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (maskTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(maskTexId), 0)
            maskTexId = 0
        }
        if (activeWallTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(activeWallTexId), 0)
            activeWallTexId = 0
        }
        if (activeFloorTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(activeFloorTexId), 0)
            activeFloorTexId = 0
        }
        if (prevWallTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(prevWallTexId), 0)
            prevWallTexId = 0
        }
        if (prevFloorTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(prevFloorTexId), 0)
            prevFloorTexId = 0
        }
        isEnabled = false
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vertexShader)
        GLES20.glAttachShader(p, fragmentShader)
        GLES20.glLinkProgram(p)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("SurfaceCompositor program link failed: $log")
        }
        return p
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("SurfaceCompositor shader compile failed ($type): $log")
        }
        return shader
    }
}
