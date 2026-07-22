package com.arkanefans.mnn_engine.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

class MnnModelValidator {
    data class ValidationResult(
        val config: JsonObject,
        val warnings: List<String>,
    )

    fun validate(modelDir: File): ValidationResult {
        require(modelDir.isDirectory) { "Model directory does not exist." }
        val configFile = File(modelDir, CONFIG_FILE)
        require(configFile.isFile && configFile.canRead() && configFile.length() > 0) {
            "config.json is missing, empty, or unreadable."
        }
        val root = runCatching {
            JsonParser.parseString(configFile.readText(Charsets.UTF_8)).asJsonObject
        }.getOrElse { error ->
            throw IllegalArgumentException("config.json is invalid: ${error.message}", error)
        }

        val llmModel = root.stringValue("llm_model")
        require(!llmModel.isNullOrBlank()) { "config.json must define llm_model." }

        val warnings = mutableListOf<String>()
        val backend = root.stringValue("backend_type")
        if (!backend.isNullOrBlank() && backend.lowercase() != "cpu") {
            warnings += "backend_type '$backend' will be overridden to cpu in phase 1."
        }
        if (!root.stringValue("visual_model").isNullOrBlank()) {
            warnings += "visual_model is present but multimodal inference is not supported in phase 1."
        }
        if (!root.stringValue("audio_model").isNullOrBlank()) {
            warnings += "audio_model is present but audio inference is not supported in phase 1."
        }

        REFERENCED_FILE_KEYS.forEach { key ->
            root.stringValue(key)?.takeIf(String::isNotBlank)?.let { relativePath ->
                validateReferencedFile(modelDir, key, relativePath)
            }
        }
        return ValidationResult(root, warnings)
    }

    private fun validateReferencedFile(modelDir: File, key: String, configuredPath: String) {
        val rawFile = File(configuredPath)
        require(!rawFile.isAbsolute) { "$key must use a relative path." }
        val canonicalRoot = modelDir.canonicalFile
        val referenced = File(canonicalRoot, configuredPath).canonicalFile
        val allowedPrefix = canonicalRoot.path + File.separator
        require(referenced.path.startsWith(allowedPrefix)) { "$key escapes the model directory." }
        require(referenced.isFile) { "$key references a missing file: $configuredPath" }
    }

    private fun JsonObject.stringValue(key: String): String? {
        val element = get(key) ?: return null
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            element.asString
        } else {
            null
        }
    }

    companion object {
        const val CONFIG_FILE = "config.json"
        private val REFERENCED_FILE_KEYS = listOf(
            "llm_model",
            "llm_weight",
            "embedding_model",
            "embedding_file",
            "tokenizer_file",
            "visual_model",
            "audio_model",
        )
    }
}
