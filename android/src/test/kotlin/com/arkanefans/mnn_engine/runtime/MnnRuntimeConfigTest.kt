package com.arkanefans.mnn_engine.runtime

import com.arkanefans.mnn_engine.MnnEngineOperationException
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MnnRuntimeConfigTest {
    @Test
    fun defaultIsCpuAndNpuDoesNotAliasHexagon() {
        assertEquals(MnnBackend.CPU, MnnLoadOptions.fromMap(null).backend)
        for (backend in MnnBackend.entries) {
            assertEquals(backend, MnnLoadOptions.fromMap(mapOf("backend" to backend.wireName)).backend)
        }
        for (invalid in listOf("npu", "auto", "OpenCL", "", 3)) {
            val error = assertFailsWith<MnnEngineOperationException> {
                MnnLoadOptions.fromMap(mapOf("backend" to invalid))
            }
            assertEquals("invalid_backend", error.code)
        }
    }

    @Test
    fun backendOverridePreservesModelConfigurationAndSeparatesCaches() {
        val directory = Files.createTempDirectory("mnn-runtime-config").toFile()
        try {
            val source = JsonParser.parseString("""{
                "backend_type":"cpu", "thread_num":3, "use_mmap":true,
                "temperature":0.6, "precision":"low", "mllm":{"backend_type":"cpu"}
            }""").asJsonObject
            val original = source.deepCopy()
            val cpu = MnnRuntimeConfig.create(source, MnnLoadOptions(), directory, "3.6.1", 16)
            val gpu = MnnRuntimeConfig.create(source, MnnLoadOptions(MnnBackend.OPENCL), directory, "3.6.1", 16)
            val nextVersion = MnnRuntimeConfig.create(source, MnnLoadOptions(MnnBackend.OPENCL), directory, "3.6.2", 16)
            assertEquals(original, source)
            assertEquals("opencl", gpu.get("backend_type").asString)
            assertFalse(gpu.get("use_mmap").asBoolean)
            assertEquals(3, gpu.get("thread_num").asInt)
            assertEquals(0.6, gpu.get("temperature").asDouble)
            assertEquals(original.get("mllm"), gpu.get("mllm"))
            assertNotEquals(cpu.get("tmp_path"), gpu.get("tmp_path"))
            assertNotEquals(gpu.get("tmp_path"), nextVersion.get("tmp_path"))
            assertTrue(File(gpu.get("tmp_path").asString).isDirectory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingThreadsUseTheExistingBoundedCpuDefault() {
        val directory = Files.createTempDirectory("mnn-runtime-threads").toFile()
        try {
            val source = JsonParser.parseString("{}").asJsonObject
            assertEquals(8, MnnRuntimeConfig.create(source, MnnLoadOptions(), directory, "3.6.1", 24)
                .get("thread_num").asInt)
            assertEquals(1, MnnRuntimeConfig.create(source, MnnLoadOptions(), directory, "3.6.1", 0)
                .get("thread_num").asInt)
        } finally {
            directory.deleteRecursively()
        }
    }
}
