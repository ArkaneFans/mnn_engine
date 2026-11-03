package com.arkanefans.mnn_engine.runtime

import java.io.File

internal object MnnRuntimeCache {
    fun sizeBytes(runtimeDir: File, modelKey: String? = null): Long {
        val target = directory(runtimeDir, modelKey)
        if (!target.exists()) return 0
        return target.walkTopDown().filter(File::isFile).sumOf(File::length)
    }

    fun clear(runtimeDir: File, modelKey: String? = null): Long {
        val size = sizeBytes(runtimeDir, modelKey)
        val target = directory(runtimeDir, modelKey)
        if (target.exists()) {
            check(target.deleteRecursively()) { "Failed to delete mmap cache at ${target.absolutePath}" }
        }
        if (modelKey == null && !runtimeDir.exists()) {
            check(runtimeDir.mkdirs()) { "Failed to recreate runtime directory." }
        }
        return size
    }

    fun directory(runtimeDir: File, modelKey: String?): File {
        if (modelKey == null) return runtimeDir
        require(modelKey.isNotBlank() && modelKey != "." && modelKey != ".." &&
            modelKey.none { it == '/' || it == '\\' || it.code in 0..31 }) {
            "Model ID must be a single directory name."
        }
        return File(runtimeDir, modelKey)
    }
}
