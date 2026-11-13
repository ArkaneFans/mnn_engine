package com.arkanefans.mnn_engine.server

import android.content.Context
import android.os.SystemClock
import com.arkanefans.mnn_engine.MnnEngineOperationException
import com.arkanefans.mnn_engine.logging.MnnLogStore
import com.arkanefans.mnn_engine.model.MnnModelInfo
import com.arkanefans.mnn_engine.runtime.MnnRuntimeManager
import com.google.gson.JsonPrimitive
import org.mockito.Mockito
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real CIO sockets, with only the synchronous native call replaced by latches. */
class MnnOpenAiServerCancellationTest {
    @Test
    fun thinkingSwitchAlsoSelectsTheCorrectOpenAiOutputFieldWithAndWithoutTools() {
        Fixture(thinkingTemplate = true).use { f ->
            f.allowReturn.countDown()
            assertEquals(200, f.post().first)
            for (stream in listOf(false, true)) {
                for (hasTools in listOf(false, true)) {
                    val disabled = f.post(stream = stream, hasTools = hasTools, extraFields = """"enable_thinking":false""")
                    assertEquals(200, disabled.first)
                    assertTrue(disabled.second.contains("\"content\":\"ok\""))
                    assertFalse(disabled.second.contains("reasoning_content"))
                    val enabled = f.post(stream = stream, hasTools = hasTools, extraFields = """"enable_thinking":true""")
                    assertEquals(200, enabled.first)
                    assertTrue(enabled.second.contains("\"reasoning_content\":\"ok\""))
                    assertFalse(enabled.second.contains("\"content\":\"ok\""))
                }
            }
        }
    }

    @Test
    fun thinkingSwitchReachesRuntimeInBothResponseModesAndConflictsReturn400() {
        Fixture().use { f ->
            f.allowReturn.countDown()
            for (stream in listOf(false, true)) {
                assertEquals(200, f.post(stream = stream, extraFields = """"enable_thinking":false""").first)
                assertEquals(200, f.post(stream = stream, extraFields = """"reasoning_effort":"high"""").first)
                assertEquals(200, f.post(stream = stream).first)
            }
            assertEquals(listOf(false, true, null, false, true, null), f.thinkingControls)
            val invalid = f.post(extraFields = """"enable_thinking":true,"reasoning_effort":"none"""")
            assertEquals(400, invalid.first)
            assertTrue(invalid.second.contains("invalid_request"))
            assertEquals(6, f.generations.get())
        }
    }

    @Test
    fun servesTheHostModelIdEvenWhenTheSourceDirectoryHasAnotherName() {
        val id = "Qwen3.5-0.8B-MNN"
        Fixture(modelId = id, displayName = id, modelDirPath = "external/original-folder").use { f ->
            val wrong = f.post(requestedModel = "original-folder")
            assertEquals(404, wrong.first)
            assertTrue(wrong.second.contains("model_not_found"))
            assertEquals(0, f.generations.get())
            val models = URL("http://127.0.0.1:${f.port}/v1/models").openStream()
                .bufferedReader().use { it.readText() }
            assertTrue(models.contains("\"id\":\"$id\""))
            f.allowReturn.countDown()
            val response = f.post(requestedModel = id)
            assertEquals(200, response.first)
            assertTrue(response.second.contains("\"model\":\"$id\""))
            assertEquals(1, f.generations.get())
        }
    }

    @Test
    fun disconnectDuringPrefillCancelsNativeAndRetainsAdmissionUntilItReturns() {
        assertStreamingDisconnect(hasTools = false)
    }

    @Test
    fun disconnectWhileToolOutputIsBufferedStillCancelsNative() {
        assertStreamingDisconnect(hasTools = true)
    }

    @Test
    fun heartbeatsContinueWhileToolTokensAreBuffered() {
        Fixture(bufferToolOutput = true).use { f ->
            f.openRequest(stream = true, hasTools = true).use { socket ->
                val input = readInitialEvent(socket)
                assertTrue(f.started.await(5, TimeUnit.SECONDS))
                repeat(2) { assertEquals(": keep-alive\n\n", readChunk(input)) }
                assertEquals(0, f.cancellations.get())
                f.allowReturn.countDown()
            }
        }
    }

    @Test
    fun rejectedRequestCannotCancelTheCurrentHttpClient() {
        Fixture().use { f ->
            f.openRequest(stream = true).use { socket ->
                readInitialEvent(socket)
                assertTrue(f.started.await(5, TimeUnit.SECONDS))
                assertEquals(429, f.post().first)
                assertEquals(0, f.cancellations.get())
                assertEquals(1, f.generations.get())
                f.allowReturn.countDown()
            }
        }
    }

    @Test
    fun successfulStreamRetainsOpenAiTextAndCompletionFrames() {
        Fixture().use { f ->
            f.allowReturn.countDown()
            assertEquals(200, f.post().first)
            val response = f.post(stream = true)
            assertEquals(200, response.first)
            assertTrue(response.second.contains("\"content\":\"ok\""))
            assertTrue(response.second.contains("\"finish_reason\":\"stop\""))
            assertTrue(response.second.endsWith("data: [DONE]\n\n"))
            assertEquals(0, f.cancellations.get())
        }
    }

    private fun assertStreamingDisconnect(hasTools: Boolean) {
        Fixture(bufferToolOutput = hasTools).use { f ->
            f.openRequest(stream = true, hasTools = hasTools, connectionClose = true).use { socket ->
                readInitialEvent(socket)
                assertTrue(f.started.await(5, TimeUnit.SECONDS))
                // Connection: close ends CIO's request-reader loop. Cancellation
                // must therefore propagate from a heartbeat write, not a reader
                // coroutine that happens to observe the reset.
                socket.setSoLinger(true, 0)
            }
            assertTrue(f.cancelled.await(5, TimeUnit.SECONDS), "Disconnect must signal native cancellation during prefill")
            assertEquals(1, f.cancellations.get())
            assertEquals(429, f.post().first, "Cancellation alone must not release admission")
            assertEquals(1, f.generations.get())

            f.allowReturn.countDown()
            assertTrue(f.returned.await(5, TimeUnit.SECONDS))
            val next = f.postWhenIdle()
            assertEquals(200, next.first)
            assertTrue(next.second.contains("\"content\":\"ok\""))
            assertEquals(1, f.cancellations.get(), "A late disconnect must not cancel the next request")
        }
    }

    @Test
    fun resetOfNonStreamingConnectionCancelsWhileNativeIsBlocked() {
        Fixture().use { f ->
            f.openRequest(stream = false).use { socket ->
                assertTrue(f.started.await(5, TimeUnit.SECONDS))
                socket.setSoLinger(true, 0) // TCP reset, before any response body exists.
            }
            assertTrue(f.cancelled.await(5, TimeUnit.SECONDS))
            assertEquals(429, f.post().first)
            f.allowReturn.countDown()
            assertEquals(200, f.postWhenIdle().first)
        }
    }

    @Test
    fun halfClosedNonStreamingRequestStillReceivesItsJsonResponse() {
        Fixture().use { f ->
            f.openRequest(stream = false).use { socket ->
                socket.shutdownOutput() // Legal HTTP: finished sending, still reading.
                assertTrue(f.started.await(5, TimeUnit.SECONDS))
                assertFalse(f.cancelled.await(200, TimeUnit.MILLISECONDS))
                f.allowReturn.countDown()
                val reader = socket.getInputStream().bufferedReader()
                assertTrue(reader.readLine().contains("200 OK"))
                var line: String
                do { line = reader.readLine() } while (line.isNotEmpty())
                // CIO sends a single JSON chunk (or a content-length body).
                var body = reader.readLine()
                if (!body.startsWith("{")) body = reader.readLine()
                assertTrue(body.contains("\"object\":\"chat.completion\""))
                assertEquals(0, f.cancellations.get())
            }
        }
    }

    @Test
    fun serverStopWaitsForNativeReturnBeforeReportingStopped() {
        Fixture().use { f ->
            f.openRequest(stream = true).use { socket ->
                readInitialEvent(socket)
                assertTrue(f.started.await(5, TimeUnit.SECONDS))
                val stop = f.executor.submit { f.server.stop() }
                assertTrue(f.cancelled.await(5, TimeUnit.SECONDS))
                assertFalse(stop.isDone)
                assertNotNull(f.server.info())
                f.allowReturn.countDown()
                stop.get(10, TimeUnit.SECONDS)
                assertNull(f.server.info())
            }
        }
    }

    @Test
    fun stopTimeoutKeepsOwnershipAndCanBeRetriedAfterNativeReturns() {
        Fixture().use { f ->
            f.openRequest(stream = true).use { socket ->
                readInitialEvent(socket)
                assertTrue(f.started.await(5, TimeUnit.SECONDS))
                val stop = f.executor.submit { f.server.stop() }
                val error = assertFailsWith<ExecutionException> { stop.get(15, TimeUnit.SECONDS) }
                assertEquals("server_stop_timeout", (error.cause as MnnEngineOperationException).code)
                assertNotNull(f.server.info())
                assertFalse(f.server.info()!!.running)
                assertFailsWith<IllegalStateException> { f.server.start(MnnBindMode.LOOPBACK, f.port, null) }
                f.allowReturn.countDown()
                assertTrue(f.returned.await(5, TimeUnit.SECONDS))
                f.server.stop()
                assertNull(f.server.info())
            }
        }
    }

    private class Fixture(
        bufferToolOutput: Boolean = false,
        modelId: String = "qwen",
        displayName: String = "Qwen",
        modelDirPath: String = "unused",
        thinkingTemplate: Boolean = false,
    ) : AutoCloseable {
        private val directory = Files.createTempDirectory("mnn-http-cancel").toFile()
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val generations = AtomicInteger()
        val cancellations = AtomicInteger()
        val thinkingControls = CopyOnWriteArrayList<Boolean?>()
        val executor = Executors.newSingleThreadExecutor()
        private val configPath = if (thinkingTemplate) {
            File(directory, "thinking-config.json").apply {
                writeText("""{"jinja":{"context":{"enable_thinking":true},"chat_template":"assistant: <think>"}}""")
            }.absolutePath
        } else "$modelDirPath/config.json"
        private val model = MnnModelInfo(
            modelId = modelId, modelKey = modelId, displayName = displayName, vendor = null,
            modelDirPath = modelDirPath, configPath = configPath, sizeBytes = 0,
            importedAt = 0, isActive = true, backend = "cpu", supportsToolCalling = true,
        )
        private val runtime = Mockito.mock(MnnRuntimeManager::class.java) { invocation ->
            when (invocation.method.name) {
                "activeModel" -> model
                "cancelGeneration" -> {
                    cancellations.incrementAndGet()
                    cancelled.countDown()
                    null
                }
                "generate" -> {
                    thinkingControls.add(invocation.getArgument<Boolean?>(5))
                    val onToken = invocation.getArgument<(String) -> Boolean>(7)
                    if (generations.incrementAndGet() == 1) {
                        started.countDown()
                        try {
                            if (bufferToolOutput) {
                                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
                                while (!allowReturn.await(25, TimeUnit.MILLISECONDS)) {
                                    check(System.nanoTime() < deadline)
                                    if (onToken("buffered tool arguments ")) break
                                }
                            }
                            check(allowReturn.await(30, TimeUnit.SECONDS))
                        } finally {
                            returned.countDown()
                        }
                    } else {
                        assertFalse(onToken("ok"))
                    }
                    MnnRuntimeManager.GenerationResult(1, 1, 1, 1, 1, "stop")
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val port = ServerSocket(0).use { it.localPort }
        val server = MnnOpenAiServer(
            Mockito.mock(Context::class.java).also { Mockito.`when`(it.filesDir).thenReturn(directory) },
            runtime, { emptyMap() }, Mockito.mock(MnnLogStore::class.java),
        )

        init {
            Mockito.mockStatic(SystemClock::class.java).use { server.start(MnnBindMode.LOOPBACK, port, null) }
        }

        fun openRequest(stream: Boolean, hasTools: Boolean = false, connectionClose: Boolean = false): Socket {
            val tools = if (hasTools) """, "tools":[{"type":"function","function":{"name":"clock","parameters":{"type":"object"}}}]""" else ""
            val body = """{"messages":[{"role":"user","content":"hello"}],"stream":$stream$tools}""".toByteArray()
            return Socket("127.0.0.1", port).also { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().apply {
                    write(("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\n" +
                        (if (connectionClose) "Connection: close\r\n" else "") +
                        "Content-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray())
                    write(body)
                    flush()
                }
            }
        }

        fun post(stream: Boolean = false, requestedModel: String? = null, extraFields: String = "", hasTools: Boolean = false): Pair<Int, String> {
            val connection = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val modelField = requestedModel?.let { "\"model\":${JsonPrimitive(it)}," }.orEmpty()
                val extras = extraFields.takeIf { it.isNotEmpty() }?.let { ",$it" }.orEmpty()
                val tools = if (hasTools) """, "tools":[{"type":"function","function":{"name":"clock","parameters":{"type":"object"}}}]""" else ""
                connection.outputStream.use {
                    it.write("""{$modelField"messages":[{"role":"user","content":"hello"}],"stream":$stream$extras$tools}""".toByteArray())
                }
                val status = connection.responseCode
                return status to (if (status >= 400) connection.errorStream else connection.inputStream)
                    .bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

        fun postWhenIdle(): Pair<Int, String> {
            repeat(100) {
                val response = post()
                if (response.first != 429) return response
                Thread.sleep(20)
            }
            error("Admission was never released after native return")
        }

        override fun close() {
            allowReturn.countDown()
            server.stop()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    private fun readInitialEvent(socket: Socket): BufferedInputStream {
        val input = BufferedInputStream(socket.getInputStream())
        assertTrue(line(input).contains("200 OK"))
        while (line(input).isNotEmpty()) { /* headers */ }
        assertTrue(readChunk(input).contains("data: "))
        return input
    }

    private fun readChunk(input: BufferedInputStream): String {
        val length = line(input).toInt(16)
        val chunk = ByteArray(length)
        var offset = 0
        while (offset < length) offset += input.read(chunk, offset, length - offset).also { check(it > 0) }
        assertEquals("", line(input))
        return chunk.toString(Charsets.UTF_8)
    }

    private fun line(input: BufferedInputStream): String = buildString {
        while (true) {
            val b = input.read()
            check(b >= 0) { "Connection closed before the response completed" }
            if (b == 10) break
            if (b != 13) append(b.toChar())
        }
    }
}
