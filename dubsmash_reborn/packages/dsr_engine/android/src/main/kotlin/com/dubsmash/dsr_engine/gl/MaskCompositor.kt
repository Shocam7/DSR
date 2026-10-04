// Copyright (c) 2026 DubSmash Reborn. All rights reserved.

package com.dubsmash.dsr_engine.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class MaskCompositor {
    companion object {
        private const val VERTEX_SHADER_MASK = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER_BACKGROUND_COLOR = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sCamera;
            uniform sampler2D sMask;
            uniform vec2 uTexelSize;
            uniform vec4 uBgColor;
            uniform float uThreshold;
            uniform float uSmoothEdge;
            void main() {
                vec4 cameraColor = texture2D(sCamera, vTexCoord);
                float mask = texture2D(sMask, vTexCoord).r;
                
                // High-resolution camera luminance edge detection for guided snapping
                float lumCenter = dot(cameraColor.rgb, vec3(0.299, 0.587, 0.114));
                float lumN = dot(texture2D(sCamera, vTexCoord + vec2(0.0, uTexelSize.y)).rgb, vec3(0.299, 0.587, 0.114));
                float lumS = dot(texture2D(sCamera, vTexCoord - vec2(0.0, uTexelSize.y)).rgb, vec3(0.299, 0.587, 0.114));
                float lumE = dot(texture2D(sCamera, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
                float lumW = dot(texture2D(sCamera, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
                float camEdge = length(vec2(lumE - lumW, lumN - lumS));
                
                // Snap coarse mask to camera boundary edge
                float guidedMask = mix(mask, step(uThreshold, mask), clamp(camEdge * 2.5, 0.0, 0.75));
                float maskedAlpha = smoothstep(uThreshold - uSmoothEdge, uThreshold + uSmoothEdge, guidedMask);
                gl_FragColor = mix(uBgColor, cameraColor, maskedAlpha);
            }
        """

        private const val FRAGMENT_SHADER_BACKGROUND_BLUR = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sCamera;
            uniform sampler2D sMask;
            uniform int uBlurRadius;
            uniform vec2 uTexelSize;
            uniform float uThreshold;
            uniform float uSmoothEdge;
            
            void main() {
                vec4 cameraColor = texture2D(sCamera, vTexCoord);
                float mask = texture2D(sMask, vTexCoord).r;
                
                float lumCenter = dot(cameraColor.rgb, vec3(0.299, 0.587, 0.114));
                float lumN = dot(texture2D(sCamera, vTexCoord + vec2(0.0, uTexelSize.y)).rgb, vec3(0.299, 0.587, 0.114));
                float lumS = dot(texture2D(sCamera, vTexCoord - vec2(0.0, uTexelSize.y)).rgb, vec3(0.299, 0.587, 0.114));
                float lumE = dot(texture2D(sCamera, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
                float lumW = dot(texture2D(sCamera, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
                float camEdge = length(vec2(lumE - lumW, lumN - lumS));
                
                float guidedMask = mix(mask, step(uThreshold, mask), clamp(camEdge * 2.5, 0.0, 0.75));
                float maskedAlpha = smoothstep(uThreshold - uSmoothEdge, uThreshold + uSmoothEdge, guidedMask);
                
                vec4 blurred = vec4(0.0);
                float radius = float(uBlurRadius);
                vec2 offset = radius * uTexelSize;
                
                blurred += texture2D(sCamera, vTexCoord + vec2(-offset.x, -offset.y));
                blurred += texture2D(sCamera, vTexCoord + vec2(0.0, -offset.y));
                blurred += texture2D(sCamera, vTexCoord + vec2(offset.x, -offset.y));
                blurred += texture2D(sCamera, vTexCoord + vec2(-offset.x, 0.0));
                blurred += texture2D(sCamera, vTexCoord);
                blurred += texture2D(sCamera, vTexCoord + vec2(offset.x, 0.0));
                blurred += texture2D(sCamera, vTexCoord + vec2(-offset.x, offset.y));
                blurred += texture2D(sCamera, vTexCoord + vec2(0.0, offset.y));
                blurred += texture2D(sCamera, vTexCoord + vec2(offset.x, offset.y));
                blurred /= 9.0;
                
                gl_FragColor = mix(blurred, cameraColor, maskedAlpha);
            }
        """
    }

    private var colorProgram = 0
    private var blurProgram = 0
    private var maskTextureId = 0
    private var maskWidth = 0
    private var maskHeight = 0
    private var enabled = false
    private var hasMask = false

    fun isEnabled(): Boolean = enabled
    /** True only when the effect is on AND a real mask has been uploaded. */
    fun isActive(): Boolean = enabled && hasMask
    private var useBlur = false
    private var bgColor = floatArrayOf(0f, 0f, 0f, 1f)
    private var blurRadius = 3
    private var threshold = 0.62f
    private var smoothEdge = 0.08f
    
    // handles for color program
    private var colorPosHandle = 0
    private var colorTexHandle = 0
    private var colorCameraHandle = 0
    private var colorMaskHandle = 0
    private var colorTexelHandle = 0
    private var colorBgHandle = 0
    private var colorThresholdHandle = 0
    private var colorSmoothHandle = 0

    // handles for blur program
    private var blurPosHandle = 0
    private var blurTexHandle = 0
    private var blurCameraHandle = 0
    private var blurMaskHandle = 0
    private var blurRadiusHandle = 0
    private var blurTexelHandle = 0
    private var blurThresholdHandle = 0
    private var blurSmoothHandle = 0
    
    private var quadBuf: FloatBuffer? = null
    private var texBuf: FloatBuffer? = null

    fun init(quadBuffer: FloatBuffer, texBuffer: FloatBuffer) {
        quadBuf = quadBuffer
        texBuf = texBuffer
        
        colorProgram = createProgram(VERTEX_SHADER_MASK, FRAGMENT_SHADER_BACKGROUND_COLOR)
        colorPosHandle = GLES20.glGetAttribLocation(colorProgram, "aPosition")
        colorTexHandle = GLES20.glGetAttribLocation(colorProgram, "aTexCoord")
        colorCameraHandle = GLES20.glGetUniformLocation(colorProgram, "sCamera")
        colorMaskHandle = GLES20.glGetUniformLocation(colorProgram, "sMask")
        colorTexelHandle = GLES20.glGetUniformLocation(colorProgram, "uTexelSize")
        colorBgHandle = GLES20.glGetUniformLocation(colorProgram, "uBgColor")
        colorThresholdHandle = GLES20.glGetUniformLocation(colorProgram, "uThreshold")
        colorSmoothHandle = GLES20.glGetUniformLocation(colorProgram, "uSmoothEdge")

        blurProgram = createProgram(VERTEX_SHADER_MASK, FRAGMENT_SHADER_BACKGROUND_BLUR)
        blurPosHandle = GLES20.glGetAttribLocation(blurProgram, "aPosition")
        blurTexHandle = GLES20.glGetAttribLocation(blurProgram, "aTexCoord")
        blurCameraHandle = GLES20.glGetUniformLocation(blurProgram, "sCamera")
        blurMaskHandle = GLES20.glGetUniformLocation(blurProgram, "sMask")
        blurRadiusHandle = GLES20.glGetUniformLocation(blurProgram, "uBlurRadius")
        blurTexelHandle = GLES20.glGetUniformLocation(blurProgram, "uTexelSize")
        blurThresholdHandle = GLES20.glGetUniformLocation(blurProgram, "uThreshold")
        blurSmoothHandle = GLES20.glGetUniformLocation(blurProgram, "uSmoothEdge")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        maskTextureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun updateMask(maskPixels: FloatArray, w: Int, h: Int) {
        // 8-bit luminance is core GLES2; float textures need OES_texture_float.
        val bytes = ByteArray(w * h)
        // Flip rows vertically so that Row 0 (top of mask/head from MediaPipe)
        // is stored at row (h - 1) of bytes, which glTexImage2D maps to V = 1.0 (top of texture).
        // This ensures sMask has standard OpenGL UV orientation: (0,0)=bottom-left, (1,1)=top-right,
        // matching sCamera (fboTextureId) exactly.
        for (y in 0 until h) {
            val srcRow = (h - 1 - y) * w
            val dstRow = y * w
            for (x in 0 until w) {
                val v = maskPixels[srcRow + x]
                bytes[dstRow + x] = (if (v <= 0f) 0 else if (v >= 1f) 255 else (v * 255f).toInt()).toByte()
            }
        }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.position(0)

        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
        if (w != maskWidth || h != maskHeight) {
            maskWidth = w
            maskHeight = h
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                w, h, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buffer
            )
        } else {
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0,
                0, 0, w, h,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buffer
            )
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        hasMask = true
    }

    fun composite(cameraFboTexId: Int, outputFboId: Int, width: Int, height: Int) {
        if (!isActive()) return

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFboId)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val program = if (useBlur) blurProgram else colorProgram
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cameraFboTexId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)

        val qb = quadBuf ?: return
        val tb = texBuf ?: return

        if (useBlur) {
            GLES20.glUniform1i(blurCameraHandle, 0)
            GLES20.glUniform1i(blurMaskHandle, 1)
            GLES20.glUniform1i(blurRadiusHandle, blurRadius)
            GLES20.glUniform2f(blurTexelHandle, 1.0f / width, 1.0f / height)
            GLES20.glUniform1f(blurThresholdHandle, threshold)
            GLES20.glUniform1f(blurSmoothHandle, smoothEdge)

            qb.position(0)
            GLES20.glEnableVertexAttribArray(blurPosHandle)
            GLES20.glVertexAttribPointer(blurPosHandle, 2, GLES20.GL_FLOAT, false, 0, qb)

            tb.position(0)
            GLES20.glEnableVertexAttribArray(blurTexHandle)
            GLES20.glVertexAttribPointer(blurTexHandle, 2, GLES20.GL_FLOAT, false, 0, tb)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(blurPosHandle)
            GLES20.glDisableVertexAttribArray(blurTexHandle)
        } else {
            GLES20.glUniform1i(colorCameraHandle, 0)
            GLES20.glUniform1i(colorMaskHandle, 1)
            GLES20.glUniform2f(colorTexelHandle, 1.0f / width, 1.0f / height)
            GLES20.glUniform4fv(colorBgHandle, 1, bgColor, 0)
            GLES20.glUniform1f(colorThresholdHandle, threshold)
            GLES20.glUniform1f(colorSmoothHandle, smoothEdge)

            qb.position(0)
            GLES20.glEnableVertexAttribArray(colorPosHandle)
            GLES20.glVertexAttribPointer(colorPosHandle, 2, GLES20.GL_FLOAT, false, 0, qb)

            tb.position(0)
            GLES20.glEnableVertexAttribArray(colorTexHandle)
            GLES20.glVertexAttribPointer(colorTexHandle, 2, GLES20.GL_FLOAT, false, 0, tb)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(colorPosHandle)
            GLES20.glDisableVertexAttribArray(colorTexHandle)
        }
        
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun setEnabled(v: Boolean) {
        enabled = v
        if (!v) {
            hasMask = false
        }
    }
    fun setUseBlur(v: Boolean) { useBlur = v }
    fun setBgColor(r: Float, g: Float, b: Float) { bgColor = floatArrayOf(r, g, b, 1f) }
    fun setBlurRadius(r: Int) { blurRadius = r }
    fun setThreshold(t: Float) { threshold = t }
    fun setSmoothEdge(e: Float) { smoothEdge = e }

    fun dispose() {
        if (colorProgram != 0) {
            GLES20.glDeleteProgram(colorProgram)
            colorProgram = 0
        }
        if (blurProgram != 0) {
            GLES20.glDeleteProgram(blurProgram)
            blurProgram = 0
        }
        if (maskTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(maskTextureId), 0)
            maskTextureId = 0
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
            error("Could not link program: " + info)
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
            error("Could not compile shader type " + type + ": " + info)
        }
        return shader
    }
}
