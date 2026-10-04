// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
package com.dubsmash.dsr_engine.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

class ThemeManifestParserTest {

    private val validManifestJson = """
    {
      "schema": 1,
      "theme_id": "th_harrenhal_01",
      "universe": "uni_dark_medieval",
      "variant": "var_ruined_keep",
      "mode": "inspired",
      "surfaces": {
        "wall": {
          "albedo": "textures/wall_stone_a.ktx2",
          "normal": "textures/wall_stone_n.ktx2",
          "tile_m": 1.2
        },
        "floor": {
          "albedo": "textures/floor_cobble_a.ktx2",
          "tile_m": 0.8
        }
      },
      "props": [
        {
          "match": "bottle",
          "asset": "props/goblet_03.glb",
          "scale": 1.0
        }
      ],
      "atmosphere": {
        "lut": "luts/grade_ash.cube",
        "fog": 0.25,
        "particles": "embers_low"
      },
      "audio": {
        "ambience": "audio/hall_wind.ogg",
        "gain_db": -22.0
      },
      "stages": ["base", "textures", "props"],
      "expires": "2026-10-08T00:00:00Z"
    }
    """.trimIndent()

    @Test
    fun testParseValidManifest() {
        val manifest = ThemeManifestParser.parse(validManifestJson)
        assertEquals(1, manifest.schema)
        assertEquals("th_harrenhal_01", manifest.themeId)
        assertEquals("uni_dark_medieval", manifest.universe)
        assertEquals("var_ruined_keep", manifest.variant)
        assertEquals("inspired", manifest.mode)
        assertEquals(2, manifest.surfaces.size)

        val wall = manifest.surfaces["wall"]
        assertNotNull(wall)
        assertEquals("textures/wall_stone_a.ktx2", wall!!.albedo)
        assertEquals("textures/wall_stone_n.ktx2", wall.normal)
        assertEquals(1.2f, wall.tileM, 0.001f)

        assertEquals(1, manifest.props.size)
        assertEquals("bottle", manifest.props[0].match)
        assertEquals("props/goblet_03.glb", manifest.props[0].asset)

        assertNotNull(manifest.atmosphere)
        assertEquals("luts/grade_ash.cube", manifest.atmosphere!!.lut)
        assertEquals(0.25f, manifest.atmosphere!!.fog, 0.001f)
        assertEquals("embers_low", manifest.atmosphere!!.particles)

        assertNotNull(manifest.audio)
        assertEquals("audio/hall_wind.ogg", manifest.audio!!.ambience)
        assertEquals(-22.0f, manifest.audio!!.gainDb, 0.001f)

        assertEquals(listOf("base", "textures", "props"), manifest.stages)
        assertEquals("2026-10-08T00:00:00Z", manifest.expires)
    }

    @Test
    fun testVersionMismatchRejected() {
        val futureJson = validManifestJson.replace("\"schema\": 1", "\"schema\": 2")
        try {
            ThemeManifestParser.parse(futureJson)
            fail("Expected ThemeVersionException for schema version 2")
        } catch (e: ThemeVersionException) {
            assertEquals(1, e.expectedVersion)
            assertEquals(2, e.actualVersion)
        }
    }

    @Test
    fun testMissingRequiredFieldRejected() {
        val missingIdJson = validManifestJson.replace("\"theme_id\": \"th_harrenhal_01\",", "")
        try {
            ThemeManifestParser.parse(missingIdJson)
            fail("Expected ThemeValidationException for missing theme_id")
        } catch (e: ThemeValidationException) {
            // expected
        }
    }

    @Test
    fun testPathTraversalInAlbedoRejected() {
        val attackJson = validManifestJson.replace("textures/wall_stone_a.ktx2", "../../../../etc/passwd")
        try {
            ThemeManifestParser.parse(attackJson)
            fail("Expected ThemeValidationException for path traversal in albedo")
        } catch (e: ThemeValidationException) {
            // expected
        }
    }

    @Test
    fun testAbsolutePathRejected() {
        val attackJson = validManifestJson.replace("textures/wall_stone_a.ktx2", "/sdcard/malicious.ktx2")
        try {
            ThemeManifestParser.parse(attackJson)
            fail("Expected ThemeValidationException for absolute path")
        } catch (e: ThemeValidationException) {
            // expected
        }
    }

    @Test
    fun testPathTraversalInPropAssetRejected() {
        val attackJson = validManifestJson.replace("props/goblet_03.glb", "foo/../../bar.glb")
        try {
            ThemeManifestParser.parse(attackJson)
            fail("Expected ThemeValidationException for traversal in prop asset")
        } catch (e: ThemeValidationException) {
            // expected
        }
    }

    @Test
    fun testNullByteInjectionRejected() {
        val attackJson = validManifestJson.replace("textures/wall_stone_a.ktx2", "textures/wall\u0000.ktx2")
        try {
            ThemeManifestParser.parse(attackJson)
            fail("Expected ThemeValidationException for null byte")
        } catch (e: ThemeValidationException) {
            // expected
        }
    }

    @Test
    fun testInvalidTileMRejected() {
        val invalidTileJson = validManifestJson.replace("\"tile_m\": 1.2", "\"tile_m\": -0.5")
        try {
            ThemeManifestParser.parse(invalidTileJson)
            fail("Expected ThemeValidationException for negative tile_m")
        } catch (e: ThemeValidationException) {
            // expected
        }
    }
}
