package com.arkanefans.mnn_engine.model

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MnnExternalModelInfoTest {
    @Test
    fun defaultsToTheDirectoryNameAndAcceptsAHostModelIdWithoutValidatingFiles() {
        val root = Files.createTempDirectory("external-mnn").toFile()
        try {
            val path = root.resolve("missing").path
            val first = MnnExternalModelInfo.read(path)
            val alias = MnnExternalModelInfo.read(path, modelId = "Alias")
            assertEquals("missing", first.modelId)
            assertEquals("missing", first.displayName)
            assertEquals("Alias", alias.modelId)
            assertEquals("Alias", alias.modelKey)
            assertEquals(path, alias.modelDirPath)
            assertEquals("Alias", alias.displayName)
            assertTrue(alias.validationWarnings.isEmpty())
            assertEquals(0L, alias.sizeBytes)
            assertFalse(root.resolve("missing").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun reportsTheDirectorySizeWithoutCopyingWeights() {
        val root = Files.createTempDirectory("external-mnn").toFile()
        try {
            val config = root.resolve("config.json").apply { writeText("{}") }
            val weights = root.resolve("weights/llm.mnn.weight").apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(2048))
            }
            val info = MnnExternalModelInfo.read(root.path, modelId = "My Qwen")
            assertEquals(config.length() + weights.length(), info.sizeBytes)
            assertEquals(root.path, info.modelDirPath)
            assertEquals("My Qwen", info.modelId)
            assertEquals(2, root.walkTopDown().filter { it.isFile }.count())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun readsCapabilitiesWithoutCheckingReferencedModelFiles() {
        val root = Files.createTempDirectory("external-mnn").toFile()
        try {
            root.resolve("config.json").writeText("""{"is_visual":true,"llm_model":"missing.mnn"}""")
            root.resolve("llm_config.json").writeText("""{"jinja":{"chat_template":"tools tool_call tool_response"}}""")
            val info = MnnExternalModelInfo.read(root.path)
            assertTrue(info.supportsVision)
            assertTrue(info.supportsToolCalling)
            assertTrue(info.validationWarnings.isEmpty())
            assertEquals(2, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
}
