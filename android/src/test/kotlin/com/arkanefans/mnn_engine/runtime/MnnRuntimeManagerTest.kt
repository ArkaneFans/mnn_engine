package com.arkanefans.mnn_engine.runtime

import android.util.Log
import com.arkanefans.mnn_engine.MnnEngineOperationException
import com.arkanefans.mnn_engine.logging.MnnLogStore
import com.arkanefans.mnn_engine.model.MnnModelInfo
import com.arkanefans.mnn_engine.model.MnnTestDirectories
import com.arkanefans.mnn_engine.model.MnnTestModelRepository
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class MnnRuntimeManagerTest {
    @Test
    fun generationFailureReleasesTheModelAndDoesNotReuseItOnReload() {
        Mockito.mockStatic(Log::class.java).use {
            for (failure in listOf(
                IllegalStateException("stage=prefill, status=INTERNAL_ERROR(4)"),
                IllegalStateException("stage=decode, status=TIMEOUT(5)"),
                IllegalStateException(),
            )) {
                val fixture = Fixture { throw failure }

                assertSame(failure, assertFailsWith<IllegalStateException> { fixture.generate() })

                Mockito.verify(fixture.session).close()
                assertNull(fixture.manager.activeModel())
                assertEquals("unloaded", fixture.snapshots.last().modelState)
                assertEquals("idle", fixture.snapshots.last().generationState)
                assertNull(fixture.snapshots.last().activeModel)
                assertEquals(failure.message ?: "MNN generation failed.", fixture.snapshots.last().lastError)

                // JVM tests have no Android MNN libraries. Reaching capability
                // validation proves that load attempted a fresh session instead
                // of reporting success with the failed resident model.
                val reloadError = assertFailsWith<MnnEngineOperationException> {
                    fixture.manager.load(fixture.model.modelId)
                }
                assertEquals("backend_unavailable", reloadError.code)
                Mockito.verify(fixture.session).close()
            }
        }
    }

    @Test
    fun normalLengthAndCancelledGenerationsKeepTheSessionReusable() {
        Mockito.mockStatic(Log::class.java).use {
            for (reason in listOf("stop", "length", "cancelled")) {
                val fixture = Fixture { metrics(reason) }

                assertEquals(reason, fixture.generate().finishReason)
                assertSame(fixture.model, fixture.manager.load(fixture.model.modelId))
                assertEquals(reason, fixture.generate().finishReason)

                assertEquals(2, fixture.generationCalls)
                assertSame(fixture.model, fixture.manager.activeModel())
                assertEquals("loaded", fixture.snapshots.last().modelState)
                assertNull(fixture.snapshots.last().lastError)
                Mockito.verify(fixture.session, Mockito.never()).close()
            }
        }
    }

    @Test
    fun explicitUnloadAfterGenerationFailureDoesNotCloseTheSessionAgain() {
        Mockito.mockStatic(Log::class.java).use {
            val fixture = Fixture { throw IllegalStateException("generation failed") }
            assertFailsWith<IllegalStateException> { fixture.generate() }

            fixture.manager.unload()
            fixture.manager.release()

            assertNull(fixture.manager.activeModel())
            Mockito.verify(fixture.session).close()
        }
    }

    @Test
    fun cleanupFailurePreservesTheInferenceErrorAndClearsTheResidentModel() {
        Mockito.mockStatic(Log::class.java).use {
            val failure = IllegalStateException("native generation failed")
            val cleanupFailure = IllegalStateException("native release failed")
            val fixture = Fixture { throw failure }
            Mockito.doThrow(cleanupFailure).`when`(fixture.session).close()

            assertSame(failure, assertFailsWith<IllegalStateException> { fixture.generate() })

            assertNull(fixture.manager.activeModel())
            assertSame(cleanupFailure, failure.suppressed.single())
            assertEquals(failure.message, fixture.snapshots.last().lastError)
            assertEquals("idle", fixture.snapshots.last().generationState)
            fixture.manager.unload()
            Mockito.verify(fixture.session).close()
        }
    }

    private class Fixture(generate: () -> MnnNativeSession.GenerationMetrics) {
        val model = MnnModelInfo(
            modelId = "qwen", modelKey = "qwen", displayName = "Qwen", vendor = null,
            modelDirPath = "unused", configPath = "unused/config.json", sizeBytes = 0,
            importedAt = 0, isActive = true, backend = "cpu",
        )
        val snapshots = mutableListOf<RuntimeSnapshot>()
        var generationCalls = 0
            private set
        val session = Mockito.mock(MnnNativeSession::class.java) { invocation ->
            if (invocation.method.name == "generate") {
                generationCalls++
                generate()
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        private val repository = Mockito.mock(MnnTestModelRepository::class.java).also {
            Mockito.`when`(it.find(model.modelId, model.modelId)).thenReturn(model)
            Mockito.`when`(it.find(model.modelId, null)).thenReturn(model)
        }
        val manager = MnnRuntimeManager(
            Mockito.mock(MnnTestDirectories::class.java), repository, MnnLogStore(),
        ) { modelState, generationState, activeModel, lastError ->
            snapshots.add(RuntimeSnapshot(
                modelState = modelState, generationState = generationState,
                activeModel = activeModel?.toMap(), lastError = lastError,
            ))
        }.also {
            // Start from a resident model without loading Android native code.
            // Exercise the real manager's generation and recovery paths below.
            setResidentField(it, "nativeSession", session)
            setResidentField(it, "activeModel", model)
        }

        fun generate() = manager.generate(
            messages = listOf(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", "hello")
            }),
            tools = JsonArray(), temperature = null, topP = null, maxTokens = 8,
            onToken = { false },
        )

        private fun setResidentField(manager: MnnRuntimeManager, name: String, value: Any) {
            MnnRuntimeManager::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(manager, value)
            }
        }
    }

    private fun metrics(reason: String) = MnnNativeSession.GenerationMetrics(
        promptTokens = 1, completionTokens = 1, prefillUs = 1,
        decodeUs = 1, sampleUs = 1, finishReason = reason,
    )
}
