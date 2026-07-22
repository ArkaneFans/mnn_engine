package com.arkanefans.mnn_engine.model

import android.content.Context
import java.io.File

class MnnTestDirectories(context: Context) {
    val rootDir = File(context.filesDir, "mnn_test")
    val modelsDir = File(rootDir, "models")
    val stagingDir = File(rootDir, "staging")
    val runtimeDir = File(rootDir, "runtime")
    val diagnosticsDir = File(rootDir, "diagnostics")

    fun ensureCreated() {
        listOf(rootDir, modelsDir, stagingDir, runtimeDir, diagnosticsDir).forEach { dir ->
            check(dir.exists() || dir.mkdirs()) { "Failed to create ${dir.absolutePath}" }
        }
    }

    fun modelDir(modelKey: String) = File(modelsDir, modelKey)
    fun modelRuntimeDir(modelKey: String) = File(runtimeDir, modelKey)
}
