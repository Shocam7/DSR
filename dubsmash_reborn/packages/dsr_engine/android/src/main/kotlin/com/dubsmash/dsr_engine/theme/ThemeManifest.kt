// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
package com.dubsmash.dsr_engine.theme

import java.io.File

class ThemeValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ThemeVersionException(val expectedVersion: Int, val actualVersion: Int) :
    Exception("Unsupported theme schema version: expected $expectedVersion, found $actualVersion")

data class SurfaceConfig(
    val albedo: String,
    val normal: String? = null,
    val tileM: Float = 1.0f
)

data class PropConfig(
    val match: String,
    val asset: String,
    val scale: Float = 1.0f
)

data class AtmosphereConfig(
    val lut: String? = null,
    val fog: Float = 0.0f,
    val particles: String? = null
)

data class AudioConfig(
    val ambience: String? = null,
    val gainDb: Float = 0.0f
)

data class ThemeManifest(
    val schema: Int,
    val themeId: String,
    val universe: String,
    val variant: String,
    val mode: String,
    val surfaces: Map<String, SurfaceConfig>,
    val props: List<PropConfig> = emptyList(),
    val atmosphere: AtmosphereConfig? = null,
    val audio: AudioConfig? = null,
    val stages: List<String> = emptyList(),
    val expires: String? = null
)

/**
 * Pure Kotlin parser and security validator for Theme Manifest Schema v1.
 * Operates without relying on Android framework stubs or external libraries.
 */
object ThemeManifestParser {
    const val CURRENT_SCHEMA_VERSION = 1

    @Throws(ThemeValidationException::class, ThemeVersionException::class)
    fun parse(jsonStr: String): ThemeManifest {
        if (jsonStr.isBlank()) {
            throw ThemeValidationException("Manifest JSON cannot be empty")
        }

        val root = try {
            JsonParser(jsonStr).parse() as? Map<*, *>
                ?: throw ThemeValidationException("Root must be a JSON object")
        } catch (e: Exception) {
            throw ThemeValidationException("Malformed manifest JSON: ${e.message}", e)
        }

        if (!root.containsKey("schema")) {
            throw ThemeValidationException("Missing required field: schema")
        }

        val schemaNumber = root["schema"] as? Number
            ?: throw ThemeValidationException("Field 'schema' must be an integer")
        val schema = schemaNumber.toInt()
        if (schema != CURRENT_SCHEMA_VERSION) {
            throw ThemeVersionException(CURRENT_SCHEMA_VERSION, schema)
        }

        val themeId = requireNonEmptyString(root, "theme_id")
        val universe = requireNonEmptyString(root, "universe")
        val variant = requireNonEmptyString(root, "variant")
        val mode = requireNonEmptyString(root, "mode")

        // Surfaces
        val surfaces = mutableMapOf<String, SurfaceConfig>()
        val rawSurfaces = root["surfaces"]
        if (rawSurfaces != null) {
            val surfacesMap = rawSurfaces as? Map<*, *>
                ?: throw ThemeValidationException("'surfaces' must be an object")
            for ((keyObj, valObj) in surfacesMap) {
                val key = keyObj.toString()
                val surfaceMap = valObj as? Map<*, *>
                    ?: throw ThemeValidationException("Surface '$key' must be an object")
                val albedo = requireSafeRelativePath(surfaceMap, "albedo", "surfaces.$key.albedo")
                val normal = if (surfaceMap.containsKey("normal")) {
                    requireSafeRelativePath(surfaceMap, "normal", "surfaces.$key.normal")
                } else null
                val tileM = (surfaceMap["tile_m"] as? Number)?.toFloat() ?: 1.0f
                if (tileM <= 0f) {
                    throw ThemeValidationException("tile_m must be positive, got: $tileM for surface: $key")
                }
                surfaces[key] = SurfaceConfig(albedo = albedo, normal = normal, tileM = tileM)
            }
        }

        // Props
        val props = mutableListOf<PropConfig>()
        val rawProps = root["props"]
        if (rawProps != null) {
            val propsList = rawProps as? List<*>
                ?: throw ThemeValidationException("'props' must be an array")
            for ((index, item) in propsList.withIndex()) {
                val propMap = item as? Map<*, *>
                    ?: throw ThemeValidationException("Prop at index $index must be an object")
                val match = requireNonEmptyString(propMap, "match")
                val asset = requireSafeRelativePath(propMap, "asset", "props[$index].asset")
                val scale = (propMap["scale"] as? Number)?.toFloat() ?: 1.0f
                props.add(PropConfig(match = match, asset = asset, scale = scale))
            }
        }

        // Atmosphere
        var atmosphere: AtmosphereConfig? = null
        val rawAtmo = root["atmosphere"]
        if (rawAtmo != null) {
            val atmoMap = rawAtmo as? Map<*, *>
                ?: throw ThemeValidationException("'atmosphere' must be an object")
            val lut = if (atmoMap.containsKey("lut")) {
                requireSafeRelativePath(atmoMap, "lut", "atmosphere.lut")
            } else null
            val fog = (atmoMap["fog"] as? Number)?.toFloat() ?: 0.0f
            val rawParticles = atmoMap["particles"]?.toString()?.trim()
            val particles = if (!rawParticles.isNullOrEmpty()) {
                validateSafeFileName(rawParticles, "atmosphere.particles")
                rawParticles
            } else null
            atmosphere = AtmosphereConfig(lut = lut, fog = fog, particles = particles)
        }

        // Audio
        var audio: AudioConfig? = null
        val rawAudio = root["audio"]
        if (rawAudio != null) {
            val audioMap = rawAudio as? Map<*, *>
                ?: throw ThemeValidationException("'audio' must be an object")
            val ambience = if (audioMap.containsKey("ambience")) {
                requireSafeRelativePath(audioMap, "ambience", "audio.ambience")
            } else null
            val gainDb = (audioMap["gain_db"] as? Number)?.toFloat() ?: 0.0f
            audio = AudioConfig(ambience = ambience, gainDb = gainDb)
        }

        // Stages
        val stages = mutableListOf<String>()
        val rawStages = root["stages"]
        if (rawStages != null) {
            val stagesList = rawStages as? List<*>
                ?: throw ThemeValidationException("'stages' must be an array")
            for (stage in stagesList) {
                stages.add(stage.toString())
            }
        }

        val expires = root["expires"]?.toString()

        return ThemeManifest(
            schema = schema,
            themeId = themeId,
            universe = universe,
            variant = variant,
            mode = mode,
            surfaces = surfaces,
            props = props,
            atmosphere = atmosphere,
            audio = audio,
            stages = stages,
            expires = expires
        )
    }

    private fun requireNonEmptyString(map: Map<*, *>, key: String): String {
        if (!map.containsKey(key)) {
            throw ThemeValidationException("Missing required field: $key")
        }
        val value = map[key]?.toString()
        if (value.isNullOrBlank()) {
            throw ThemeValidationException("Field '$key' cannot be blank")
        }
        return value
    }

    private fun requireSafeRelativePath(map: Map<*, *>, key: String, context: String): String {
        val path = requireNonEmptyString(map, key)
        validateSafeRelativePath(path, context)
        return path
    }

    fun validateSafeRelativePath(path: String, context: String) {
        if (path.contains("\u0000")) {
            throw ThemeValidationException("Path traversal attempt (null byte) in $context: $path")
        }
        if (path.startsWith("/") || path.startsWith("\\")) {
            throw ThemeValidationException("Absolute paths are forbidden in $context: $path")
        }
        if (path.contains(":") && !path.startsWith("assets:")) {
            throw ThemeValidationException("Colon/protocol forbidden in path in $context: $path")
        }

        val normalized = File(path).normalize().path
        if (normalized.startsWith("..") || normalized.contains("/../") || normalized.contains("\\..\\")) {
            throw ThemeValidationException("Path traversal attempt in $context: $path")
        }
        val segments = path.split('/', '\\')
        for (segment in segments) {
            if (segment == "..") {
                throw ThemeValidationException("Path traversal attempt in $context: $path")
            }
        }
    }

    fun validateSafeFileName(name: String, context: String) {
        if (name.contains("/") || name.contains("\\") || name.contains("..") || name.contains("\u0000")) {
            throw ThemeValidationException("Invalid file name or traversal in $context: $name")
        }
    }

    /**
     * Minimal pure-Kotlin JSON parser.
     */
    private class JsonParser(private val src: String) {
        private var idx = 0

        fun parse(): Any? {
            skipWhitespace()
            val result = parseValue()
            skipWhitespace()
            if (idx < src.length) {
                throw IllegalArgumentException("Unexpected character at index $idx: '${src[idx]}'")
            }
            return result
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (idx >= src.length) throw IllegalArgumentException("Unexpected EOF")
            return when (val ch = src[idx]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected character '$ch' at index $idx")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            match('{')
            val map = mutableMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                match('}')
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                match(':')
                skipWhitespace()
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (peek() == ',') {
                    match(',')
                } else if (peek() == '}') {
                    match('}')
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or '}' at index $idx")
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            match('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                match(']')
                return list
            }
            while (true) {
                skipWhitespace()
                val value = parseValue()
                list.add(value)
                skipWhitespace()
                if (peek() == ',') {
                    match(',')
                } else if (peek() == ']') {
                    match(']')
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or ']' at index $idx")
                }
            }
            return list
        }

        private fun parseString(): String {
            match('"')
            val sb = StringBuilder()
            while (idx < src.length) {
                val ch = src[idx++]
                if (ch == '"') {
                    return sb.toString()
                } else if (ch == '\\') {
                    if (idx >= src.length) throw IllegalArgumentException("Unterminated escape")
                    when (val esc = src[idx++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (idx + 4 > src.length) throw IllegalArgumentException("Invalid unicode escape")
                            val hex = src.substring(idx, idx + 4)
                            sb.append(hex.toInt(16).toChar())
                            idx += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = idx
            if (src[idx] == '-') idx++
            while (idx < src.length && src[idx] in '0'..'9') idx++
            var isFloat = false
            if (idx < src.length && src[idx] == '.') {
                isFloat = true
                idx++
                while (idx < src.length && src[idx] in '0'..'9') idx++
            }
            if (idx < src.length && (src[idx] == 'e' || src[idx] == 'E')) {
                isFloat = true
                idx++
                if (idx < src.length && (src[idx] == '+' || src[idx] == '-')) idx++
                while (idx < src.length && src[idx] in '0'..'9') idx++
            }
            val numStr = src.substring(start, idx)
            return if (isFloat) numStr.toDouble() else numStr.toLong()
        }

        private fun parseBoolean(): Boolean {
            if (src.startsWith("true", idx)) {
                idx += 4
                return true
            }
            if (src.startsWith("false", idx)) {
                idx += 4
                return false
            }
            throw IllegalArgumentException("Invalid boolean literal at index $idx")
        }

        private fun parseNull(): Any? {
            if (src.startsWith("null", idx)) {
                idx += 4
                return null
            }
            throw IllegalArgumentException("Invalid null literal at index $idx")
        }

        private fun peek(): Char = if (idx < src.length) src[idx] else '\u0000'

        private fun match(expected: Char) {
            skipWhitespace()
            if (idx >= src.length || src[idx] != expected) {
                throw IllegalArgumentException("Expected '$expected' at index $idx, found '${peek()}'")
            }
            idx++
        }

        private fun skipWhitespace() {
            while (idx < src.length && src[idx].isWhitespace()) {
                idx++
            }
        }
    }
}
