package com.arkanefans.mnn_engine.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

class MnnTestModelRepository(
    private val directories: MnnTestDirectories,
    private val validator: MnnModelValidator,
) {
    fun list(activeModelId: String? = null): List<MnnModelInfo> {
        directories.ensureCreated()
        return directories.modelsDir.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .mapNotNull { dir -> modelInfo(dir, activeModelId) }
            .sortedBy { it.displayName.lowercase() }
    }

    fun find(modelId: String, activeModelId: String? = null): MnnModelInfo? {
        val modelKey = modelId.removePrefix("local/")
        if (modelId.startsWith("local/") && modelKey.isNotBlank() &&
            !modelKey.contains('/') && !modelKey.contains('\\')) {
            return modelInfo(directories.modelDir(modelKey), activeModelId)
        }
        return list(activeModelId).firstOrNull { it.modelId == modelId }
    }

    fun delete(modelId: String, activeModelId: String?) {
        require(modelId != activeModelId) { "The active model cannot be deleted." }
        val model = find(modelId, activeModelId) ?: throw IllegalArgumentException("Model not found: $modelId")
        val modelsRoot = directories.modelsDir.canonicalFile
        val target = File(model.modelDirPath).canonicalFile
        require(target.parentFile == modelsRoot) { "Model path is outside the test model directory." }
        check(target.deleteRecursively()) { "Failed to delete ${target.absolutePath}" }
        directories.modelRuntimeDir(model.modelKey).takeIf(File::exists)?.deleteRecursively()
    }

    fun modelInfo(dir: File, activeModelId: String? = null): MnnModelInfo? {
        if (!dir.isDirectory || !File(dir, MnnModelValidator.CONFIG_FILE).isFile) return null
        val metadata = readMarketMetadata(dir)
        val modelKey = dir.name
        val modelId = metadata.string("modelId", "model_id")?.takeIf(String::isNotBlank)
            ?: "local/$modelKey"
        val validationResult = runCatching { validator.validate(dir) }
        val validation = validationResult.getOrNull()
        val warnings = validation?.warnings
            ?: listOf(validationResult.exceptionOrNull()?.message ?: "Model validation failed.")
        return MnnModelInfo(
            modelId = modelId,
            modelKey = modelKey,
            displayName = metadata.string("modelName", "model_name", "name")
                ?.takeIf(String::isNotBlank) ?: modelKey,
            vendor = metadata.string("vendor"),
            modelDirPath = dir.absolutePath,
            configPath = File(dir, MnnModelValidator.CONFIG_FILE).absolutePath,
            sizeBytes = directorySize(dir),
            importedAt = dir.lastModified(),
            isActive = modelId == activeModelId,
            supportsVision = validation?.supportsVision == true,
            supportsToolCalling = validation?.supportsToolCalling == true,
            validationWarnings = warnings,
        )
    }

    private fun readMarketMetadata(dir: File): JsonObject {
        val file = File(dir, "market_config.json")
        if (!file.isFile) return JsonObject()
        return runCatching { JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject }
            .getOrDefault(JsonObject())
    }

    private fun JsonObject.string(vararg keys: String): String? {
        for (key in keys) {
            val value = get(key) ?: continue
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) return value.asString
        }
        return null
    }

    private fun directorySize(root: File): Long {
        return root.walkTopDown().filter(File::isFile).sumOf(File::length)
    }
}
