package com.arkanefans.mnn_engine.model

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.arkanefans.mnn_engine.logging.MnnLogStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MnnModelImporter(
    private val context: Context,
    private val directories: MnnTestDirectories,
    private val validator: MnnModelValidator,
    private val repository: MnnTestModelRepository,
    private val logStore: MnnLogStore,
) {
    fun import(treeUri: Uri, replaceExisting: Boolean, activeModelId: String?): MnnModelInfo {
        directories.ensureCreated()
        val source = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Unable to open the selected directory.")
        require(source.isDirectory && source.canRead()) { "Selected directory is not readable." }

        val staging = File(directories.stagingDir, UUID.randomUUID().toString())
        check(staging.mkdirs()) { "Failed to create import staging directory." }
        logStore.info(TAG, "Importing ${source.name ?: treeUri} into ${staging.absolutePath}")
        try {
            copyDirectoryContents(source, staging)
            validator.validate(staging)
            val modelKey = resolveModelKey(staging, source.name)
            val finalDir = directories.modelDir(modelKey)
            val existing = repository.modelInfo(finalDir, activeModelId)
            if (existing != null) {
                require(replaceExisting) { "Model already exists: ${existing.modelId}" }
                require(existing.modelId != activeModelId) { "The active model cannot be replaced." }
                check(finalDir.deleteRecursively()) { "Failed to replace existing model." }
            }
            if (!staging.renameTo(finalDir)) {
                check(staging.copyRecursively(finalDir, overwrite = true)) {
                    "Failed to commit imported model."
                }
                check(staging.deleteRecursively()) { "Failed to clean import staging directory." }
            }
            finalDir.setLastModified(System.currentTimeMillis())
            val result = repository.modelInfo(finalDir, activeModelId)
                ?: throw IllegalStateException("Imported model could not be scanned.")
            logStore.info(TAG, "Imported ${result.modelId} (${result.sizeBytes} bytes)")
            return result
        } catch (error: Throwable) {
            staging.deleteRecursively()
            logStore.error(TAG, "Model import failed", error)
            throw error
        }
    }

    private fun copyDirectoryContents(source: DocumentFile, target: File) {
        source.listFiles().forEach { child ->
            val name = child.name ?: throw IllegalArgumentException("Selected directory contains an unnamed entry.")
            require(isSafeName(name)) { "Unsafe file name: $name" }
            val output = File(target, name)
            if (child.isDirectory) {
                check(output.mkdir()) { "Failed to create directory: $name" }
                copyDirectoryContents(child, output)
            } else if (child.isFile) {
                val input = context.contentResolver.openInputStream(child.uri)
                    ?: throw IllegalArgumentException("Unable to read: $name")
                input.use { sourceStream ->
                    FileOutputStream(output).use { targetStream ->
                        sourceStream.copyTo(targetStream, DEFAULT_BUFFER_SIZE)
                    }
                }
            }
        }
    }

    private fun resolveModelKey(staging: File, sourceName: String?): String {
        val metadataFile = File(staging, "market_config.json")
        val metadata = if (metadataFile.isFile) {
            runCatching { JsonParser.parseString(metadataFile.readText(Charsets.UTF_8)).asJsonObject }
                .getOrDefault(JsonObject())
        } else {
            JsonObject()
        }
        val displayName = listOf("modelName", "model_name", "name", "modelId", "model_id")
            .firstNotNullOfOrNull { key ->
                metadata.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
            ?: sourceName
            ?: "mnn-model-${UUID.randomUUID().toString().take(8)}"
        return sanitize(displayName)
    }

    private fun sanitize(value: String): String {
        val normalized = value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(80)
            .trimEnd('-')
        return normalized.ifBlank { "mnn-model-${UUID.randomUUID().toString().take(8)}" }
    }

    private fun isSafeName(name: String): Boolean {
        return name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')
    }

    private companion object {
        const val TAG = "MnnModelImporter"
    }
}
