package com.arkanefans.mnn_engine.server

import android.content.Context
import android.os.SystemClock
import com.arkanefans.mnn_engine.logging.MnnLogStore
import com.arkanefans.mnn_engine.model.MnnModelInfo
import com.arkanefans.mnn_engine.runtime.MnnNativeBridge
import com.arkanefans.mnn_engine.runtime.MnnRuntimeManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.event.Level
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class MnnOpenAiServer(
    private val context: Context,
    private val runtimeManager: MnnRuntimeManager,
    private val snapshotProvider: () -> Map<String, Any?>,
    private val logStore: MnnLogStore,
) {
    private val requestBusy = AtomicBoolean(false)
    private val mediaStager = MnnMediaStager(context, logStore)
    @Volatile
    private var engine: EmbeddedServer<*, *>? = null
    @Volatile
    private var serverInfo: MnnServerInfo? = null
    @Volatile
    private var apiKey: String? = null
    @Volatile
    private var bindMode: MnnBindMode = MnnBindMode.LOOPBACK

    fun start(mode: MnnBindMode, port: Int, configuredApiKey: String?): MnnServerInfo {
        check(engine == null) { "MNN API Server is already running." }
        mediaStager.cleanupStale()
        val startedAt = System.currentTimeMillis() / 1000
        val startMonotonic = SystemClock.elapsedRealtime()
        val created = embeddedServer(Netty, host = mode.host, port = port) {
            install(CallLogging) { level = Level.INFO }
            routing {
                get("/") {
                    logStore.debug(TAG, "GET /")
                    val html = context.assets.open(TEST_PAGE_ASSET).bufferedReader().use { it.readText() }
                    call.respondText(html, ContentType.Text.Html)
                }
                get("/health") {
                    if (!authorize()) return@get
                    logStore.debug(TAG, "GET /health")
                    respondJson(HttpStatusCode.OK, healthPayload())
                }
                get("/v1/models") {
                    if (!authorize()) return@get
                    logStore.debug(TAG, "GET /v1/models")
                    val model = runtimeManager.activeModel()
                    val data = JsonArray()
                    if (model != null) data.add(modelPayload(model))
                    respondJson(HttpStatusCode.OK, JsonObject().apply {
                        addProperty("object", "list")
                        add("data", data)
                    })
                }
                post("/v1/chat/completions") {
                    if (!authorize()) return@post
                    val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (contentLength != null && contentLength > MnnChatRequestParser.MAX_REQUEST_BYTES) {
                        respondOpenAiError(HttpStatusCode.PayloadTooLarge, "request_too_large", "Request body exceeds 16 MiB.")
                        return@post
                    }
                    val request = try {
                        MnnChatRequestParser.parse(call.receiveText())
                    } catch (error: IllegalArgumentException) {
                        logStore.warn(TAG, "Rejected invalid chat request: ${error.message}")
                        respondOpenAiError(HttpStatusCode.BadRequest, "invalid_request", error.message ?: "Invalid request.")
                        return@post
                    }
                    val activeModel = runtimeManager.activeModel()
                    if (activeModel == null) {
                        respondOpenAiError(HttpStatusCode.ServiceUnavailable, "model_not_loaded", "No MNN model is loaded.")
                        return@post
                    }
                    if (request.model != null && request.model != activeModel.modelId) {
                        respondOpenAiError(HttpStatusCode.NotFound, "model_not_found", "Only ${activeModel.modelId} is loaded.")
                        return@post
                    }
                    if (request.hasTools && !activeModel.supportsToolCalling) {
                        respondOpenAiError(HttpStatusCode.BadRequest, "model_tool_calling_not_supported", "The loaded model does not provide a tool-capable chat template.")
                        return@post
                    }
                    if (request.hasImageContent() && !activeModel.supportsVision) {
                        respondOpenAiError(HttpStatusCode.BadRequest, "model_vision_not_supported", "The loaded model does not provide a visual model.")
                        return@post
                    }
                    if (!requestBusy.compareAndSet(false, true)) {
                        respondOpenAiError(HttpStatusCode.TooManyRequests, "request_queue_full", "Another generation request is active.")
                        return@post
                    }
                    val requestId = UUID.randomUUID().toString()
                    val requestStartedAt = SystemClock.elapsedRealtime()
                    logStore.info(TAG, "$requestId POST /v1/chat/completions started")
                    try {
                        val staged = try {
                            mediaStager.stage(requestId, request.messages)
                        } catch (error: IllegalArgumentException) {
                            logStore.warn(TAG, "Rejected media request: ${error.message}")
                            respondOpenAiError(HttpStatusCode.BadRequest, "invalid_image", error.message ?: "Invalid image content.")
                            return@post
                        }
                        staged.use { stagedMessages ->
                            val messages = request.withToolPolicy(stagedMessages.messages)
                            if (request.stream) streamCompletion(request, activeModel, messages)
                            else nonStreamCompletion(request, activeModel, messages)
                        }
                    } catch (error: Throwable) {
                        logStore.error(TAG, "Generation failed", error)
                        if (!call.response.isCommitted) {
                            respondOpenAiError(HttpStatusCode.InternalServerError, "generation_failed", error.message ?: "MNN generation failed.")
                        }
                    } finally {
                        requestBusy.set(false)
                        logStore.info(TAG, "$requestId POST /v1/chat/completions completed in ${SystemClock.elapsedRealtime() - requestStartedAt}ms")
                    }
                }
            }
        }
        return try {
            created.start(wait = false)
            engine = created
            apiKey = configuredApiKey
            bindMode = mode
            MnnServerInfo(
                running = true,
                bindMode = mode.wireName,
                bindAddress = mode.host,
                port = port,
                localBaseUrl = "http://127.0.0.1:$port",
                advertisedUrls = if (mode == MnnBindMode.ALL_INTERFACES) networkUrls(port) else emptyList(),
                requiresApiKey = !configuredApiKey.isNullOrEmpty(),
                startedAt = startedAt,
                startDurationMs = SystemClock.elapsedRealtime() - startMonotonic,
            ).also { info ->
                serverInfo = info
                logStore.info(TAG, "Server started at ${info.bindAddress}:$port mode=${info.bindMode}")
            }
        } catch (error: Throwable) {
            runCatching { created.stop(0, 0) }
            throw error
        }
    }

    fun stop() {
        runtimeManager.cancelGeneration()
        engine?.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        engine = null
        serverInfo = null
        apiKey = null
        requestBusy.set(false)
        mediaStager.cleanupStale()
        logStore.info(TAG, "Server stopped")
    }

    fun info(): MnnServerInfo? = serverInfo

    private suspend fun io.ktor.server.routing.RoutingContext.authorize(): Boolean {
        val required = apiKey
        if (required.isNullOrEmpty()) return true
        val actual = call.request.headers[HttpHeaders.Authorization]
        if (actual != null && actual.startsWith("Bearer ") && constantTimeEquals(actual.removePrefix("Bearer "), required)) return true
        respondOpenAiError(HttpStatusCode.Unauthorized, "invalid_api_key", "A valid Bearer API key is required.")
        return false
    }

    private fun healthPayload(): JsonObject {
        val snapshot = snapshotProvider()
        return JsonObject().apply {
            addProperty("status", when {
                snapshot["lastError"] != null -> "error"
                snapshot["modelState"] == "loading" -> "loading"
                snapshot["serverState"] == "starting" -> "starting"
                snapshot["generationState"] == "generating" -> "generating"
                else -> "ready"
            })
            addProperty("engine", "mnn")
            addProperty("model", runtimeManager.activeModel()?.modelId)
            addProperty("mnn_version", runCatching { MnnNativeBridge.version().substringBefore(" (") }.getOrDefault("unavailable"))
            addProperty("server_started_at", serverInfo?.startedAt)
            addProperty("bind_mode", serverInfo?.bindMode)
        }
    }

    private fun modelPayload(model: MnnModelInfo) = JsonObject().apply {
        addProperty("id", model.modelId)
        addProperty("object", "model")
        addProperty("created", model.importedAt / 1000)
        addProperty("owned_by", "mnn")
    }

    private suspend fun io.ktor.server.routing.RoutingContext.nonStreamCompletion(
        request: MnnChatRequest,
        model: MnnModelInfo,
        messages: List<JsonObject>,
    ) {
        val output = StringBuilder()
        val metrics = generate(request, messages) { token -> output.append(token); false }
        val parsed = if (request.hasTools) parseTools(request, output.toString()) else MnnParsedCompletion(output.toString().ifBlank { null }, emptyList())
        val finish = if (parsed.toolCalls.isNotEmpty()) "tool_calls" else normalizeFinishReason(metrics.finishReason)
        respondJson(HttpStatusCode.OK, JsonObject().apply {
            addProperty("id", "chatcmpl-${UUID.randomUUID()}")
            addProperty("object", "chat.completion")
            addProperty("created", System.currentTimeMillis() / 1000)
            addProperty("model", model.modelId)
            add("choices", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("index", 0)
                    add("message", assistantMessage(parsed))
                    addProperty("finish_reason", finish)
                })
            })
            add("usage", usage(metrics))
        })
    }

    private suspend fun io.ktor.server.routing.RoutingContext.streamCompletion(
        request: MnnChatRequest,
        model: MnnModelInfo,
        messages: List<JsonObject>,
    ) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.response.headers.append(HttpHeaders.Connection, "keep-alive")
        call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
            val completionId = "chatcmpl-${UUID.randomUUID()}"
            val created = System.currentTimeMillis() / 1000
            var disconnected = false
            fun send(data: String): Boolean = try {
                write("data: $data\n\n"); flush(); false
            } catch (_: IOException) { disconnected = true; true }
            send(chunk(completionId, created, model.modelId, role = "assistant").toString())
            val output = StringBuilder()
            val metrics = try {
                generate(request, messages) { token ->
                    if (request.hasTools) { output.append(token); false }
                    else { output.append(token); disconnected || send(chunk(completionId, created, model.modelId, content = token).toString()) }
                }
            } catch (error: Throwable) {
                logStore.error(TAG, "Stream generation failed", error)
                if (!disconnected) {
                    send(JsonObject().apply { add("error", JsonObject().apply {
                        addProperty("message", error.message ?: "MNN generation failed.")
                        addProperty("code", "generation_failed")
                    }) }.toString())
                    send("[DONE]")
                }
                return@respondTextWriter
            }
            if (!disconnected) {
                val parsed = if (request.hasTools) parseTools(request, output.toString()) else null
                if (parsed?.content != null) send(chunk(completionId, created, model.modelId, content = parsed.content).toString())
                if (parsed != null && parsed.toolCalls.isNotEmpty()) send(toolCallsChunk(completionId, created, model.modelId, parsed.toolCalls).toString())
                send(chunk(completionId, created, model.modelId, finishReason = if (parsed?.toolCalls?.isNotEmpty() == true) "tool_calls" else normalizeFinishReason(metrics.finishReason)).toString())
                send("[DONE]")
            }
        }
    }

    private suspend fun generate(request: MnnChatRequest, messages: List<JsonObject>, onToken: (String) -> Boolean) =
        withContext(Dispatchers.IO) {
            runtimeManager.generate(messages, request.effectiveTools(), request.temperature, request.topP, request.maxTokens, onToken)
        }

    private fun parseTools(request: MnnChatRequest, output: String): MnnParsedCompletion {
        val names = request.tools.map { it.asJsonObject.getAsJsonObject("function").get("name").asString }.toSet()
        return MnnToolCallParser.parse(output, names, request.parallelToolCalls).also { parsed ->
            parsed.diagnostic?.let { logStore.warn(TAG, it) }
        }
    }

    private fun assistantMessage(parsed: MnnParsedCompletion) = JsonObject().apply {
        addProperty("role", "assistant")
        if (parsed.content == null) add("content", com.google.gson.JsonNull.INSTANCE) else addProperty("content", parsed.content)
        if (parsed.toolCalls.isNotEmpty()) add("tool_calls", toolCalls(parsed.toolCalls))
    }

    private fun toolCalls(calls: List<MnnToolCall>) = JsonArray().also { array -> calls.forEach { call ->
        array.add(JsonObject().apply {
            addProperty("id", call.id); addProperty("type", "function")
            add("function", JsonObject().apply { addProperty("name", call.name); addProperty("arguments", call.arguments) })
        })
    } }

    private fun toolCallsChunk(id: String, created: Long, model: String, calls: List<MnnToolCall>) = chunk(id, created, model).apply {
        getAsJsonArray("choices").get(0).asJsonObject.getAsJsonObject("delta").add("tool_calls", toolCalls(calls).also { calls.forEachIndexed { index, call -> it.get(index).asJsonObject.addProperty("index", index) } })
    }

    private fun chunk(id: String, created: Long, model: String, role: String? = null, content: String? = null, finishReason: String? = null) = JsonObject().apply {
        addProperty("id", id); addProperty("object", "chat.completion.chunk"); addProperty("created", created); addProperty("model", model)
        add("choices", JsonArray().apply { add(JsonObject().apply {
            addProperty("index", 0); add("delta", JsonObject().apply { role?.let { addProperty("role", it) }; content?.let { addProperty("content", it) } })
            if (finishReason == null) add("finish_reason", null) else addProperty("finish_reason", finishReason)
        }) })
    }

    private fun usage(metrics: MnnRuntimeManager.GenerationResult) = JsonObject().apply {
        addProperty("prompt_tokens", metrics.promptTokens); addProperty("completion_tokens", metrics.completionTokens); addProperty("total_tokens", metrics.promptTokens + metrics.completionTokens)
    }

    private suspend fun io.ktor.server.routing.RoutingContext.respondJson(status: HttpStatusCode, body: JsonObject) = call.respondText(body.toString(), ContentType.Application.Json, status)

    private suspend fun io.ktor.server.routing.RoutingContext.respondOpenAiError(status: HttpStatusCode, code: String, message: String) = respondJson(status, JsonObject().apply { add("error", JsonObject().apply { addProperty("message", message); addProperty("type", "invalid_request_error"); addProperty("code", code) }) })

    private fun normalizeFinishReason(reason: String): String = when (reason) { "length" -> "length"; else -> "stop" }

    private fun constantTimeEquals(actual: String, expected: String): Boolean {
        val a = actual.toByteArray(Charsets.UTF_8); val b = expected.toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.isEqual(a, b)
    }

    private fun networkUrls(port: Int): List<String> = runCatching<List<String>> {
        NetworkInterface.getNetworkInterfaces().asSequence().flatMap { network ->
            network.inetAddresses.asSequence().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.map { "http://${it.hostAddress}:$port" }
        }.distinct().sorted().toList()
    }.getOrElse { emptyList() }

    private fun MnnChatRequest.hasImageContent(): Boolean = messages.any { message ->
        message.get("content")?.isJsonArray == true && message.getAsJsonArray("content").iterator().asSequence().any { it.asJsonObject.get("type")?.asString == "image_url" }
    }

    private companion object {
        const val TAG = "server"
        const val TEST_PAGE_ASSET = "mnn_test_page.html"
    }
}
