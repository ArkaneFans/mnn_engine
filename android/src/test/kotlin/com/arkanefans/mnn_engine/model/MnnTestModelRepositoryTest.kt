package com.arkanefans.mnn_engine.model

import android.content.Context
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.mockito.Mockito

class MnnTestModelRepositoryTest {
    @Test
    fun usesTheModelKeyAsTheDefaultModelId() {
        val filesDir = createTempDirectory("mnn-repository").toFile()
        try {
            val context = Mockito.mock(Context::class.java)
            Mockito.`when`(context.filesDir).thenReturn(filesDir)
            val directories = MnnTestDirectories(context)
            directories.ensureCreated()
            val modelDir = directories.modelDir("qwen3-5-0-8b-mnn").apply { mkdirs() }
            File(modelDir, "llm.mnn").writeBytes(byteArrayOf(1))
            File(modelDir, "config.json").writeText("""{"llm_model":"llm.mnn"}""")
            val repository = MnnTestModelRepository(directories, MnnModelValidator())

            val model = repository.find("qwen3-5-0-8b-mnn")

            assertNotNull(model)
            assertEquals("qwen3-5-0-8b-mnn", model.modelId)
            assertNull(repository.find("local/qwen3-5-0-8b-mnn"))
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
