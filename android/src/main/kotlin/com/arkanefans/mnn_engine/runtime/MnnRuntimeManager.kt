package com.arkanefans.mnn_engine.runtime

import android.os.SystemClock
import com.arkanefans.mnn_engine.logging.MnnLogStore
import com.arkanefans.mnn_engine.model.MnnModelInfo
import com.arkanefans.mnn_engine.model.MnnTestDirectories
import com.arkanefans.mnn_engine.model.MnnTestModelRepository
import com.google.gson.JsonArray
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MnnRuntimeManager(
    private val directories: MnnTestDirectories,
    private val repository: MnnTestModelRepository,
    private val logStore: MnnLogStore,
    private val onStateChanged: (
        modelState: String,
        generationState: String,
        activeModel: MnnModelInfo?,
        lastError: String?,
    ) -> Unit,
) {
    data class GenerationResult(
        val promptTokens: Int,
        val completionTokens: Int,
        val prefillUs: Long,
        val decodeUs: Long,
        val sampleUs: Long,
        val finishReason: String,
    )

    private val lock = Any()
    private val generating = AtomicBoolean(false)
    @Volatile
    private var nativeSession: MnnNativeSession? = null
    @Volatile
    private var activeModel: MnnModelInfo? = null
    private var baseConfigJson: String = "{}"

    fun activeModel(): MnnModelInfo? = activeModel

    fun load(modelId: String): MnnModelInfo {
        synchronized(lock) {
            if (generating.get()) throw GenerationBusyException()
            val model = repository.find(modelId, activeModel?.modelId)
                ?: throw IllegalArgumentException("Model not found: $modelId")
            activeModel?.takeIf { it.modelId == model.modelId && nativeSession != null }?.let {
                logStore.info("runtime", "Reusing loaded model ${model.modelId}")
                return it
            }
            onStateChanged("loading", "idle", activeModel, null)
            try {
                nativeSession?.close()
                nativeSession = null
                activeModel = null
                val runtimeConfig = createRuntimeConfig(model)
                logStore.info("jni", "Creating native session for ${model.modelId}")
                val loadStartedAt = SystemClock.elapsedRealtime()
                val session = MnnNativeSession.load(model.configPath, runtimeConfig.toString())
                val loadDurationMs = SystemClock.elapsedRealtime() - loadStartedAt
                baseConfigJson = runtimeConfig.toString()
                nativeSession = session
                activeModel = model.copy(isActive = true, loadDurationMs = loadDurationMs)
                logStore.info("mnn", "Loaded model ${model.modelId} in ${loadDurationMs}ms")
                onStateChanged("loaded", "idle", activeModel, null)
                return activeModel!!
            } catch (error: Throwable) {
                nativeSession?.close()
                nativeSession = null
                activeModel = null
                logStore.error("runtime", "Failed to load ${model.modelId}", error)
                onStateChanged("error", "idle", null, error.message)
                throw error
            }
        }
    }

    fun unload() {
        synchronized(lock) {
            if (generating.get()) throw GenerationBusyException()
            if (nativeSession == null && activeModel == null) return
            onStateChanged("unloading", "idle", activeModel, null)
            nativeSession?.close()
            nativeSession = null
            activeModel = null
            baseConfigJson = "{}"
            logStore.info("runtime", "Model unloaded")
            onStateChanged("unloaded", "idle", null, null)
        }
    }

    fun generate(
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double?,
        topP: Double?,
        maxTokens: Int,
        onToken: (String) -> Boolean,
    ): GenerationResult {
        val (session, generationModel) = synchronized(lock) {
            if (!generating.compareAndSet(false, true)) {
                throw GenerationBusyException()
            }
            try {
                val currentSession = nativeSession
                    ?: throw IllegalStateException("No MNN model is loaded.")
                val currentModel = activeModel
                    ?: throw IllegalStateException("No active MNN model is available.")
                currentSession to currentModel
            } catch (error: Throwable) {
                generating.set(false)
                throw error
            }
        }
        onStateChanged("loaded", "generating", generationModel, null)
        logStore.info("request", "Generation started for ${generationModel.modelId}")
        var failureMessage: String? = null
        return try {
            val config = JsonParser.parseString(baseConfigJson).asJsonObject
            temperature?.let { config.addProperty("temperature", it) }
            topP?.let { config.addProperty("topP", it) }
            if (maxTokens >= 0) config.addProperty("max_new_tokens", maxTokens)
            val jinja = config.getAsJsonObject("jinja") ?: JsonObject().also { config.add("jinja", it) }
            val context = jinja.getAsJsonObject("context") ?: JsonObject().also { jinja.add("context", it) }
            context.add("tools", tools.deepCopy())
            val metrics = session.generate(
                Gson().toJson(messages),
                config.toString(),
                maxTokens,
                MnnNativeSession.TokenCallback(onToken),
            )
            GenerationResult(
                promptTokens = metrics.promptTokens,
                completionTokens = metrics.completionTokens,
                prefillUs = metrics.prefillUs,
                decodeUs = metrics.decodeUs,
                sampleUs = metrics.sampleUs,
                finishReason = metrics.finishReason,
            ).also {
                logStore.info(
                    "request",
                    "Generation completed: prompt=${it.promptTokens}, completion=${it.completionTokens}, finish=${it.finishReason}",
                )
            }
        } catch (error: Throwable) {
            failureMessage = error.message
            logStore.error("request", "Generation failed", error)
            throw error
        } finally {
            synchronized(lock) {
                generating.set(false)
                onStateChanged(
                    if (activeModel == null) "unloaded" else "loaded",
                    "idle",
                    activeModel,
                    failureMessage,
                )
            }
        }
    }

    fun cancelGeneration() {
        nativeSession?.cancel()
        logStore.info("request", "Generation cancellation requested")
    }

    fun release() {
        cancelGeneration()
        synchronized(lock) {
            nativeSession?.close()
            nativeSession = null
            activeModel = null
        }
    }

    private fun createRuntimeConfig(model: MnnModelInfo): JsonObject {
        val root = JsonParser.parseString(File(model.configPath).readText(Charsets.UTF_8)).asJsonObject
        root.addProperty("backend_type", "cpu")
        root.addProperty("use_mmap", false)
        if (!root.has("thread_num") || root.get("thread_num").asInt <= 0) {
            root.addProperty("thread_num", Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
        }
        val runtimeDir = directories.modelRuntimeDir(model.modelKey)
        val tempDir = File(runtimeDir, "tmp")
        check(tempDir.exists() || tempDir.mkdirs()) { "Failed to create model runtime directory." }
        root.addProperty("tmp_path", tempDir.absolutePath)
        return root
    }

    class GenerationBusyException : IllegalStateException("A generation request is already active.")

}
