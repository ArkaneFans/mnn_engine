package com.arkanefans.mnn_engine.server

import android.content.Context
import android.os.SystemClock
import com.arkanefans.mnn_engine.logging.MnnLogStore
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class MnnOpenAiServer(
    private val context: Context,
    private val runtimeManager: MnnRuntimeManager,
    private val snapshotProvider: () -> Map<String, Any?>,
    private val logStore: MnnLogStore,
) {
    private val requestBusy = AtomicBoolean(false)
    @Volatile
    private var engine: EmbeddedServer<*, *>? = null
    @Volatile
    private var serverInfo: MnnServerInfo? = null

    fun start(host: String, port: Int, apiKey: String?): MnnServerInfo {
        check(engine == null) { "MNN API Server is already running." }
        val startedAt = System.currentTimeMillis() / 1000
        val startMonotonic = SystemClock.elapsedRealtime()
        val created = embeddedServer(Netty, host = host, port = port) {
            install(CallLogging) { level = Level.INFO }
            routing {
                get("/") {
                    logStore.debug("request", "GET /")
                    val html = context.assets.open(TEST_PAGE_ASSET).bufferedReader().use { it.readText() }
                    call.respondText(html, ContentType.Text.Html)
                }
                get("/health") {
                    if (!authorize(apiKey)) return@get
                    logStore.debug("request", "GET /health")
                    respondJson(HttpStatusCode.OK, healthPayload())
                }
                get("/v1/models") {
                    if (!authorize(apiKey)) return@get
                    logStore.debug("request", "GET /v1/models")
                    val model = runtimeManager.activeModel()
                    val data = JsonArray()
                    if (model != null) {
                        data.add(JsonObject().apply {
                            addProperty("id", model.modelId)
                            addProperty("object", "model")
                            addProperty("created", model.importedAt / 1000)
                            addProperty("owned_by", "mnn")
                        })
                    }
                    respondJson(HttpStatusCode.OK, JsonObject().apply {
                        addProperty("object", "list")
                        add("data", data)
                    })
                }
                post("/v1/chat/completions") {
                    if (!authorize(apiKey)) return@post
                    val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (contentLength != null && contentLength > MnnChatRequestParser.MAX_REQUEST_BYTES) {
                        logStore.warn("request", "Rejected oversized POST /v1/chat/completions")
                        respondOpenAiError(HttpStatusCode.PayloadTooLarge, "request_too_large", "Request body exceeds 2 MiB.")
                        return@post
                    }
                    val request = try {
                        MnnChatRequestParser.parse(call.receiveText())
                    } catch (error: IllegalArgumentException) {
                        logStore.warn("request", "Rejected invalid chat request: ${error.message}")
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
                    if (!requestBusy.compareAndSet(false, true)) {
                        respondOpenAiError(
                            HttpStatusCode.TooManyRequests,
                            "request_queue_full",
                            "Another generation request is active.",
                        )
                        return@post
                    }
                    val requestId = UUID.randomUUID().toString()
                    val requestStartedAt = SystemClock.elapsedRealtime()
                    logStore.info("request", "$requestId POST /v1/chat/completions started")
                    try {
                        if (request.stream) {
                            streamCompletion(request, activeModel.modelId)
                        } else {
                            try {
                                nonStreamCompletion(request, activeModel.modelId)
                            } catch (error: Throwable) {
                                logStore.error(TAG, "Non-stream generation failed", error)
                                respondOpenAiError(
                                    HttpStatusCode.InternalServerError,
                                    "generation_failed",
                                    error.message ?: "MNN generation failed.",
                                )
                            }
                        }
                    } finally {
                        requestBusy.set(false)
                        logStore.info(
                            "request",
                            "$requestId POST /v1/chat/completions completed in ${SystemClock.elapsedRealtime() - requestStartedAt}ms",
                        )
                    }
                }
            }
        }
        return try {
            created.start(wait = false)
            engine = created
            MnnServerInfo(
                true,
                host,
                port,
                startedAt,
                SystemClock.elapsedRealtime() - startMonotonic,
            ).also { info ->
                serverInfo = info
                logStore.info(TAG, "Server started at ${info.baseUrl}")
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
        requestBusy.set(false)
        logStore.info(TAG, "Server stopped")
    }

    fun info(): MnnServerInfo? = serverInfo

    private suspend fun io.ktor.server.routing.RoutingContext.authorize(apiKey: String?): Boolean {
        if (apiKey.isNullOrEmpty()) return true
        if (call.request.headers[HttpHeaders.Authorization] == "Bearer $apiKey") return true
        respondOpenAiError(HttpStatusCode.Unauthorized, "invalid_api_key", "A valid Bearer API key is required.")
        return false
    }

    private fun healthPayload(): JsonObject {
        val snapshot = snapshotProvider()
        return JsonObject().apply {
            addProperty(
                "status",
                when {
                    snapshot["lastError"] != null -> "error"
                    snapshot["modelState"] == "loading" -> "loading"
                    snapshot["serverState"] == "starting" -> "starting"
                    snapshot["generationState"] == "generating" -> "generating"
                    else -> "ready"
                },
            )
            addProperty("engine", "mnn")
            addProperty("model", runtimeManager.activeModel()?.modelId)
            addProperty(
                "mnn_version",
                runCatching { MnnNativeBridge.version().substringBefore(" (") }
                    .getOrDefault("unavailable"),
            )
            addProperty("server_started_at", serverInfo?.startedAt)
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.nonStreamCompletion(
        request: MnnChatRequest,
        modelId: String,
    ) {
        val output = StringBuilder()
        val metrics = withContext(Dispatchers.IO) {
            runtimeManager.generate(
                messages = request.messages,
                temperature = request.temperature,
                topP = request.topP,
                maxTokens = request.maxTokens,
            ) { token ->
                output.append(token)
                false
            }
        }
        val response = JsonObject().apply {
            addProperty("id", "chatcmpl-${UUID.randomUUID()}")
            addProperty("object", "chat.completion")
            addProperty("created", System.currentTimeMillis() / 1000)
            addProperty("model", modelId)
            add("choices", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("index", 0)
                    add("message", JsonObject().apply {
                        addProperty("role", "assistant")
                        addProperty("content", output.toString())
                    })
                    addProperty("finish_reason", normalizeFinishReason(metrics.finishReason))
                })
            })
            add("usage", usage(metrics))
        }
        respondJson(HttpStatusCode.OK, response)
    }

    private suspend fun io.ktor.server.routing.RoutingContext.streamCompletion(
        request: MnnChatRequest,
        modelId: String,
    ) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.response.headers.append(HttpHeaders.Connection, "keep-alive")
        call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
            val completionId = "chatcmpl-${UUID.randomUUID()}"
            val created = System.currentTimeMillis() / 1000
            var disconnected = false
            fun send(data: String): Boolean {
                return try {
                    write("data: $data\n\n")
                    flush()
                    false
                } catch (_: IOException) {
                    disconnected = true
                    true
                }
            }
            send(chunk(completionId, created, modelId, role = "assistant").toString())
            val metrics = try {
                withContext(Dispatchers.IO) {
                    runtimeManager.generate(
                        messages = request.messages,
                        temperature = request.temperature,
                        topP = request.topP,
                        maxTokens = request.maxTokens,
                    ) { token ->
                        disconnected || send(chunk(completionId, created, modelId, content = token).toString())
                    }
                }
            } catch (error: Throwable) {
                logStore.error(TAG, "Stream generation failed", error)
                if (!disconnected) {
                    send(JsonObject().apply {
                        add("error", JsonObject().apply {
                            addProperty("message", error.message ?: "MNN generation failed.")
                            addProperty("code", "generation_failed")
                        })
                    }.toString())
                    send("[DONE]")
                }
                return@respondTextWriter
            }
            if (!disconnected) {
                send(
                    chunk(
                        completionId,
                        created,
                        modelId,
                        finishReason = normalizeFinishReason(metrics.finishReason),
                    ).toString(),
                )
                send("[DONE]")
            }
        }
    }

    private fun chunk(
        id: String,
        created: Long,
        modelId: String,
        role: String? = null,
        content: String? = null,
        finishReason: String? = null,
    ): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("object", "chat.completion.chunk")
        addProperty("created", created)
        addProperty("model", modelId)
        add("choices", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("index", 0)
                add("delta", JsonObject().apply {
                    role?.let { addProperty("role", it) }
                    content?.let { addProperty("content", it) }
                })
                if (finishReason == null) add("finish_reason", null) else addProperty("finish_reason", finishReason)
            })
        })
    }

    private fun usage(metrics: MnnRuntimeManager.GenerationResult): JsonObject = JsonObject().apply {
        addProperty("prompt_tokens", metrics.promptTokens)
        addProperty("completion_tokens", metrics.completionTokens)
        addProperty("total_tokens", metrics.promptTokens + metrics.completionTokens)
    }

    private suspend fun io.ktor.server.routing.RoutingContext.respondJson(
        status: HttpStatusCode,
        body: JsonObject,
    ) {
        call.respondText(body.toString(), ContentType.Application.Json, status)
    }

    private suspend fun io.ktor.server.routing.RoutingContext.respondOpenAiError(
        status: HttpStatusCode,
        code: String,
        message: String,
    ) {
        respondJson(status, JsonObject().apply {
            add("error", JsonObject().apply {
                addProperty("message", message)
                addProperty("type", "invalid_request_error")
                addProperty("code", code)
            })
        })
    }

    private fun normalizeFinishReason(reason: String): String = when (reason) {
        "length" -> "length"
        else -> "stop"
    }

    private companion object {
        const val TAG = "MnnOpenAiServer"
        const val TEST_PAGE_ASSET = "mnn_test_page.html"
    }
}
