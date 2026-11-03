package com.arkanefans.mnn_engine.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** Describes a path without registering, copying or validating its files. */
internal object MnnExternalModelInfo {
    fun read(path: String, modelId: String? = null, displayName: String? = null): MnnModelInfo {
        val dir = File(path).absoluteFile
        val config = readJson(File(dir, MnnModelValidator.CONFIG_FILE))
        val llmConfigPath = runCatching { config.get("llm_config")?.asString }.getOrNull()
        val llmConfig = readJson(File(dir, llmConfigPath ?: "llm_config.json"))
        val capabilities = runCatching { MnnModelValidator.capabilities(config, llmConfig) }
            .getOrDefault(false to false)
        val id = modelId ?: dir.name
        return MnnModelInfo(
            modelId = id,
            modelKey = id,
            displayName = displayName ?: id,
            vendor = null,
            modelDirPath = dir.path,
            configPath = File(dir, MnnModelValidator.CONFIG_FILE).path,
            sizeBytes = dir.walkTopDown().filter(File::isFile).sumOf(File::length),
            importedAt = System.currentTimeMillis(),
            isActive = false,
            supportsVision = capabilities.first,
            supportsToolCalling = capabilities.second,
        )
    }

    private fun readJson(file: File): JsonObject = runCatching {
        JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
    }.getOrDefault(JsonObject())
}
