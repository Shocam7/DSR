// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
package com.dubsmash.dsr_engine.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Ktx2LoaderTest {

    @Test
    fun testParseValidAstcKtx2() {
        val loader = Ktx2Loader(astcSupported = true)

        // 16x16 ASTC 4x4 block texture (4x4 blocks = 16 blocks * 16 bytes = 256 bytes)
        val dummyAstcData = ByteArray(256) { 0x42.toByte() }
        val ktx2Bytes = Ktx2Loader.createTestKtx2Bytes(
            width = 16,
            height = 16,
            vkFormat = Ktx2Loader.VK_FORMAT_ASTC_4x4_UNORM_BLOCK,
            mipLevels = listOf(dummyAstcData)
        )

        val texture = loader.loadTextureFromBytes(ktx2Bytes)
        assertEquals(16, texture.width)
        assertEquals(16, texture.height)
        assertEquals(TextureFormat.ASTC_4x4, texture.format)
        assertTrue(texture.isCompressed)
        assertEquals(256L, texture.memorySizeBytes)
    }

    @Test
    fun testFallbackToEtc2WhenAstcUnsupported_M4_A2() {
        // M4-A2: Texture loader falls back to ETC2 when ASTC is unsupported
        val loader = Ktx2Loader(astcSupported = false)

        val dummyAstcData = ByteArray(256) { 0x11.toByte() }
        val astcKtx2Bytes = Ktx2Loader.createTestKtx2Bytes(
            width = 16,
            height = 16,
            vkFormat = Ktx2Loader.VK_FORMAT_ASTC_4x4_UNORM_BLOCK,
            mipLevels = listOf(dummyAstcData)
        )

        val dummyEtc2Data = ByteArray(256) { 0x22.toByte() }
        val etc2Ktx2Bytes = Ktx2Loader.createTestKtx2Bytes(
            width = 16,
            height = 16,
            vkFormat = Ktx2Loader.VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK,
            mipLevels = listOf(dummyEtc2Data)
        )

        // Pass ASTC data with ETC2 fallback bytes
        val texture = loader.loadTextureFromBytes(astcKtx2Bytes, fallbackEtc2Bytes = etc2Ktx2Bytes)
        assertEquals(16, texture.width)
        assertEquals(16, texture.height)
        assertEquals(TextureFormat.ETC2_RGBA8, texture.format)
        assertTrue(texture.isCompressed)
        assertEquals(256L, texture.memorySizeBytes)
    }

    @Test
    fun testFileBasedEtc2FallbackWhenAstcUnsupported() {
        val tempDir = Files.createTempDirectory("ktx2_test").toFile()
        try {
            val astcFile = File(tempDir, "wall_a.ktx2")
            val etc2File = File(tempDir, "wall_a.etc2.ktx2")

            val dummyAstcData = ByteArray(512) { 0xAA.toByte() }
            astcFile.writeBytes(
                Ktx2Loader.createTestKtx2Bytes(
                    width = 32,
                    height = 32,
                    vkFormat = Ktx2Loader.VK_FORMAT_ASTC_4x4_SRGB_BLOCK,
                    mipLevels = listOf(dummyAstcData)
                )
            )

            val dummyEtc2Data = ByteArray(512) { 0xBB.toByte() }
            etc2File.writeBytes(
                Ktx2Loader.createTestKtx2Bytes(
                    width = 32,
                    height = 32,
                    vkFormat = Ktx2Loader.VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK,
                    mipLevels = listOf(dummyEtc2Data)
                )
            )

            // When ASTC is supported
            val loaderWithAstc = Ktx2Loader(astcSupported = true)
            val astcLoaded = loaderWithAstc.loadTextureFromFile(astcFile)
            assertEquals(TextureFormat.ASTC_4x4, astcLoaded.format)

            // When ASTC is unsupported -> falls back to wall_a.etc2.ktx2
            val loaderNoAstc = Ktx2Loader(astcSupported = false)
            val etc2Loaded = loaderNoAstc.loadTextureFromFile(astcFile)
            assertEquals(TextureFormat.ETC2_RGBA8, etc2Loaded.format)
            assertEquals(512L, etc2Loaded.memorySizeBytes)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testInvalidHeaderRejected() {
        val loader = Ktx2Loader(astcSupported = true)
        val badBytes = ByteArray(100) { 0x00 }
        try {
            loader.loadTextureFromBytes(badBytes)
            fail("Expected Ktx2ParseException on invalid identifier")
        } catch (e: Ktx2ParseException) {
            // Expected
        }
    }

    @Test
    fun testBufferTooSmallRejected() {
        val loader = Ktx2Loader(astcSupported = true)
        val tinyBytes = ByteArray(30)
        try {
            loader.loadTextureFromBytes(tinyBytes)
            fail("Expected Ktx2ParseException on tiny buffer")
        } catch (e: Ktx2ParseException) {
            // Expected
        }
    }
}
