package com.arkanefans.mnn_engine.runtime

import com.google.gson.JsonObject
import java.io.File

internal object MnnRuntimeConfig {
    fun create(
        source: JsonObject,
        options: MnnLoadOptions,
        runtimeDir: File,
        mnnVersion: String,
        availableProcessors: Int,
    ): JsonObject {
        val root = source.deepCopy()
        root.addProperty("backend_type", options.backend.wireName)
        root.addProperty("use_mmap", false)
        if (!root.has("thread_num") || root.get("thread_num").asInt <= 0) {
            root.addProperty("thread_num", availableProcessors.coerceIn(1, 8))
        }
        val tempDir = File(runtimeDir, "tmp/$mnnVersion/${options.backend.wireName}")
        check(tempDir.isDirectory || tempDir.mkdirs()) { "Failed to create model runtime directory." }
        root.addProperty("tmp_path", tempDir.absolutePath)
        return root
    }
}
