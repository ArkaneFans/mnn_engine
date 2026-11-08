package com.arkanefans.mnn_engine.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MnnRuntimeCacheTest {
    @Test
    fun reportsAndClearsPerModelAndGlobalCaches() {
        val root = Files.createTempDirectory("mnn-runtime-cache").toFile()
        try {
            writeFile(File(root, "qwen/tmp/3.6.1/cpu/weight.bin"), 10)
            writeFile(File(root, "other/tmp/3.6.1/opencl/mnn_cachefile.bin"), 7)
            assertEquals(17, MnnRuntimeCache.sizeBytes(root))
            assertEquals(10, MnnRuntimeCache.sizeBytes(root, "qwen"))
            assertEquals(0, MnnRuntimeCache.sizeBytes(root, "missing"))

            assertEquals(10, MnnRuntimeCache.clear(root, "qwen"))
            assertFalse(File(root, "qwen").exists())
            assertEquals(7, MnnRuntimeCache.sizeBytes(root))
            assertTrue(File(root, "other/tmp/3.6.1/opencl/mnn_cachefile.bin").isFile)

            assertEquals(7, MnnRuntimeCache.clear(root))
            assertEquals(0, MnnRuntimeCache.sizeBytes(root))
            assertTrue(root.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearsFormerModelNamesWithoutARegisteredModelAndRejectsEscapingIds() {
        val root = Files.createTempDirectory("mnn-runtime-cache").toFile()
        try {
            val runtime = File(root, "runtime")
            writeFile(File(runtime, "Old model/tmp/weights.bin"), 10)
            writeFile(File(root, "source/llm.mnn.weight"), 7)
            assertEquals(10, MnnRuntimeCache.clear(runtime, "Old model"))
            assertEquals(0, MnnRuntimeCache.sizeBytes(runtime, "Old model"))
            for (id in listOf("", ".", "..", "../source", "source/weights", "source\\weights")) {
                assertFailsWith<IllegalArgumentException> { MnnRuntimeCache.clear(runtime, id) }
            }
            assertEquals(7, File(root, "source/llm.mnn.weight").length())
        } finally { root.deleteRecursively() }
    }

    private fun writeFile(file: File, size: Int) {
        check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
        file.writeBytes(ByteArray(size))
    }
}
