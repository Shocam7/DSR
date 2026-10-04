// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
package com.dubsmash.dsr_engine.theme

import android.opengl.GLES20
import android.util.Log
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class TextureFormat {
    ASTC_4x4,
    ETC2_RGBA8,
    RGBA8,
    UNKNOWN
}

data class Ktx2Level(
    val byteOffset: Long,
    val byteLength: Long,
    val uncompressedByteLength: Long
)

data class Ktx2Header(
    val vkFormat: Int,
    val typeSize: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val pixelDepth: Int,
    val layerCount: Int,
    val faceCount: Int,
    val levelCount: Int,
    val supercompressionScheme: Int,
    val levels: List<Ktx2Level>
)

data class LoadedTexture(
    val textureId: Int,
    val width: Int,
    val height: Int,
    val format: TextureFormat,
    val isCompressed: Boolean,
    val memorySizeBytes: Long,
    val levelData: List<ByteBuffer> = emptyList()
)

class Ktx2ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * KTX2 container parser and texture loader.
 * Supports ASTC compressed textures with automatic ETC2 fallback
 * when ASTC is unsupported by the GPU hardware/driver.
 */
class Ktx2Loader(
    var astcSupported: Boolean = true
) {
    companion object {
        private const val TAG = "Ktx2Loader"

        // 12-byte KTX2 identifier: «KTX 20»\r\n\x1A\n
        val KTX2_IDENTIFIER = byteArrayOf(
            0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A
        )

        // VkFormat definitions relevant to mobile textures
        const val VK_FORMAT_UNDEFINED = 0
        const val VK_FORMAT_R8G8B8A8_UNORM = 37
        const val VK_FORMAT_R8G8B8A8_SRGB = 43
        const val VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK = 147
        const val VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK = 148
        const val VK_FORMAT_ASTC_4x4_UNORM_BLOCK = 157
        const val VK_FORMAT_ASTC_4x4_SRGB_BLOCK = 158

        // OpenGL ES Internal Formats
        const val GL_COMPRESSED_RGBA8_ETC2_EAC = 0x9278
        const val GL_COMPRESSED_RGBA_ASTC_4x4_KHR = 0x93B0
        const val GL_RGBA = GLES20.GL_RGBA

        /**
         * Detects ASTC support from the active OpenGL ES context extension string.
         */
        fun queryGlAstcSupport(): Boolean {
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: return false
            return extensions.contains("GL_KHR_texture_compression_astc_ldr") ||
                    extensions.contains("GL_OES_texture_compression_astc") ||
                    extensions.contains("GL_KHR_texture_compression_astc_hdr")
        }

        /**
         * Helper to serialize a minimal valid KTX2 file for testing / test packs.
         */
        fun createTestKtx2Bytes(
            width: Int,
            height: Int,
            vkFormat: Int,
            mipLevels: List<ByteArray>
        ): ByteArray {
            val levelCount = mipLevels.size
            // Header: 12 (id) + 4*9 (fields) + 4*4 (dfd/kvd) + 8*2 (sgd) = 80 bytes
            val headerSize = 80
            val levelIndexSize = levelCount * 24 // 3 uint64s per level
            var offset = (headerSize + levelIndexSize).toLong()

            val levelStructs = mutableListOf<Ktx2Level>()
            for (levelBytes in mipLevels) {
                levelStructs.add(
                    Ktx2Level(
                        byteOffset = offset,
                        byteLength = levelBytes.size.toLong(),
                        uncompressedByteLength = levelBytes.size.toLong()
                    )
                )
                offset += levelBytes.size
            }

            val totalSize = offset.toInt()
            val bb = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

            // 1. Identifier
            bb.put(KTX2_IDENTIFIER)
            // 2. Header fields
            bb.putInt(vkFormat)
            bb.putInt(1) // typeSize
            bb.putInt(width)
            bb.putInt(height)
            bb.putInt(0) // pixelDepth
            bb.putInt(0) // layerCount
            bb.putInt(1) // faceCount
            bb.putInt(levelCount)
            bb.putInt(0) // supercompressionScheme = None

            // Index fields
            bb.putInt(0) // dfdByteOffset
            bb.putInt(0) // dfdByteLength
            bb.putInt(0) // kvdByteOffset
            bb.putInt(0) // kvdByteLength
            bb.putLong(0L) // sgdByteOffset
            bb.putLong(0L) // sgdByteLength

            // Level index
            for (level in levelStructs) {
                bb.putLong(level.byteOffset)
                bb.putLong(level.byteLength)
                bb.putLong(level.uncompressedByteLength)
            }

            // Level data
            for (levelBytes in mipLevels) {
                bb.put(levelBytes)
            }

            return bb.array()
        }
    }

    /**
     * Parses the KTX2 header and level index from a ByteBuffer.
     */
    fun parseHeader(buffer: ByteBuffer): Ktx2Header {
        val originalOrder = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        try {
            if (buffer.remaining() < 80) {
                throw Ktx2ParseException("Buffer too small for KTX2 header: ${buffer.remaining()} bytes")
            }

            // Verify identifier
            val id = ByteArray(12)
            buffer.get(id)
            if (!id.contentEquals(KTX2_IDENTIFIER)) {
                throw Ktx2ParseException("Invalid KTX2 identifier")
            }

            val vkFormat = buffer.int
            val typeSize = buffer.int
            val pixelWidth = buffer.int
            val pixelHeight = buffer.int
            val pixelDepth = buffer.int
            val layerCount = buffer.int
            val faceCount = buffer.int
            val levelCount = buffer.int.coerceAtLeast(1)
            val supercompressionScheme = buffer.int

            val dfdByteOffset = buffer.int
            val dfdByteLength = buffer.int
            val kvdByteOffset = buffer.int
            val kvdByteLength = buffer.int
            val sgdByteOffset = buffer.long
            val sgdByteLength = buffer.long

            val levels = mutableListOf<Ktx2Level>()
            for (i in 0 until levelCount) {
                val byteOffset = buffer.long
                val byteLength = buffer.long
                val uncompressedByteLength = buffer.long
                levels.add(Ktx2Level(byteOffset, byteLength, uncompressedByteLength))
            }

            return Ktx2Header(
                vkFormat = vkFormat,
                typeSize = typeSize,
                pixelWidth = pixelWidth,
                pixelHeight = pixelHeight,
                pixelDepth = pixelDepth,
                layerCount = layerCount,
                faceCount = faceCount,
                levelCount = levelCount,
                supercompressionScheme = supercompressionScheme,
                levels = levels
            )
        } finally {
            buffer.order(originalOrder)
        }
    }

    /**
     * Loads a texture from an ASTC KTX2 file with fallback to an ETC2 variant
     * if ASTC is unsupported.
     *
     * @param primaryAstcFile The main ASTC KTX2 texture file.
     * @param fallbackEtc2File Optional fallback ETC2 KTX2 file. If omitted, attempts to find `<basename>.etc2.ktx2`.
     */
    fun loadTextureFromFile(
        primaryAstcFile: File,
        fallbackEtc2File: File? = null
    ): LoadedTexture {
        val targetFile: File
        val isFallbackNeeded = !astcSupported

        if (isFallbackNeeded) {
            val candidate = fallbackEtc2File ?: File(
                primaryAstcFile.parentFile,
                primaryAstcFile.name.replace(".ktx2", ".etc2.ktx2")
            )
            targetFile = if (candidate.exists()) {
                Log.i(TAG, "ASTC unsupported: falling back to ETC2 texture: ${candidate.name}")
                candidate
            } else {
                Log.w(TAG, "ASTC unsupported and fallback ${candidate.name} not found; attempting primary: ${primaryAstcFile.name}")
                primaryAstcFile
            }
        } else {
            targetFile = primaryAstcFile
        }

        val bytes = targetFile.readBytes()
        return loadTextureFromBytes(bytes)
    }

    /**
     * Parses and loads a texture from in-memory KTX2 bytes.
     * If the bytes are ASTC but ASTC is unsupported, converts or indicates ETC2 fallback requirement.
     */
    fun loadTextureFromBytes(
        bytes: ByteArray,
        fallbackEtc2Bytes: ByteArray? = null
    ): LoadedTexture {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var header = parseHeader(buffer)

        val isAstc = header.vkFormat == VK_FORMAT_ASTC_4x4_UNORM_BLOCK ||
                header.vkFormat == VK_FORMAT_ASTC_4x4_SRGB_BLOCK

        if (isAstc && !astcSupported) {
            Log.i(TAG, "ASTC format detected but ASTC is unsupported on this device. Using ETC2 fallback.")
            if (fallbackEtc2Bytes != null) {
                buffer = ByteBuffer.wrap(fallbackEtc2Bytes).order(ByteOrder.LITTLE_ENDIAN)
                header = parseHeader(buffer)
            } else {
                // Return texture designated as ETC2 fallback
                return createFallbackEtc2Texture(header, bytes)
            }
        }

        val format = when (header.vkFormat) {
            VK_FORMAT_ASTC_4x4_UNORM_BLOCK, VK_FORMAT_ASTC_4x4_SRGB_BLOCK -> TextureFormat.ASTC_4x4
            VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK, VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK -> TextureFormat.ETC2_RGBA8
            VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB -> TextureFormat.RGBA8
            else -> TextureFormat.UNKNOWN
        }

        val isCompressed = format == TextureFormat.ASTC_4x4 || format == TextureFormat.ETC2_RGBA8
        var totalMemory = 0L

        val levelBuffers = mutableListOf<ByteBuffer>()
        for (level in header.levels) {
            val offset = level.byteOffset.toInt()
            val length = level.byteLength.toInt()
            if (offset + length <= bytes.size) {
                val slice = ByteBuffer.wrap(bytes, offset, length)
                levelBuffers.add(slice)
                totalMemory += length
            }
        }

        return LoadedTexture(
            textureId = 0,
            width = header.pixelWidth,
            height = header.pixelHeight,
            format = format,
            isCompressed = isCompressed,
            memorySizeBytes = totalMemory,
            levelData = levelBuffers
        )
    }

    private fun createFallbackEtc2Texture(astcHeader: Ktx2Header, originalBytes: LongArray? = null): LoadedTexture {
        // Compute estimated memory size for ETC2 (128 bits / 16 bytes per 4x4 block)
        val blocksX = (astcHeader.pixelWidth + 3) / 4
        val blocksY = (astcHeader.pixelHeight + 3) / 4
        val etc2SizeBytes = (blocksX * blocksY * 16).toLong()

        return LoadedTexture(
            textureId = 0,
            width = astcHeader.pixelWidth,
            height = astcHeader.pixelHeight,
            format = TextureFormat.ETC2_RGBA8,
            isCompressed = true,
            memorySizeBytes = etc2SizeBytes
        )
    }

    private fun createFallbackEtc2Texture(astcHeader: Ktx2Header, originalBytes: ByteArray): LoadedTexture {
        val blocksX = (astcHeader.pixelWidth + 3) / 4
        val blocksY = (astcHeader.pixelHeight + 3) / 4
        val etc2SizeBytes = (blocksX * blocksY * 16).toLong()

        return LoadedTexture(
            textureId = 0,
            width = astcHeader.pixelWidth,
            height = astcHeader.pixelHeight,
            format = TextureFormat.ETC2_RGBA8,
            isCompressed = true,
            memorySizeBytes = etc2SizeBytes
        )
    }

    /**
     * Uploads the loaded texture data to OpenGL ES.
     * Must be called on a thread with an active EGL Context (e.g. `dsr-gl`).
     */
    fun uploadToGl(texture: LoadedTexture): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        if (texId == 0) {
            Log.e(TAG, "Failed to generate GL texture")
            return 0
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)

        val internalFormat = when (texture.format) {
            TextureFormat.ASTC_4x4 -> GL_COMPRESSED_RGBA_ASTC_4x4_KHR
            TextureFormat.ETC2_RGBA8 -> GL_COMPRESSED_RGBA8_ETC2_EAC
            TextureFormat.RGBA8 -> GL_RGBA
            TextureFormat.UNKNOWN -> GL_RGBA
        }

        for ((mipLevel, levelBuf) in texture.levelData.withIndex()) {
            val mipW = (texture.width shr mipLevel).coerceAtLeast(1)
            val mipH = (texture.height shr mipLevel).coerceAtLeast(1)

            levelBuf.position(0)
            if (texture.isCompressed) {
                GLES20.glCompressedTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    mipLevel,
                    internalFormat,
                    mipW,
                    mipH,
                    0,
                    levelBuf.remaining(),
                    levelBuf
                )
            } else {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    mipLevel,
                    internalFormat,
                    mipW,
                    mipH,
                    0,
                    GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    levelBuf
                )
            }
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return texId
    }
}
