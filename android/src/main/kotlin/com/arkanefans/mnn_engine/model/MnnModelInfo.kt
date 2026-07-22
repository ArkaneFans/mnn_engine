package com.arkanefans.mnn_engine.model

data class MnnModelInfo(
    val modelId: String,
    val modelKey: String,
    val displayName: String,
    val vendor: String?,
    val modelDirPath: String,
    val configPath: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val isActive: Boolean,
    val loadDurationMs: Long? = null,
    val validationWarnings: List<String> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "modelId" to modelId,
        "modelKey" to modelKey,
        "displayName" to displayName,
        "vendor" to vendor,
        "modelDirPath" to modelDirPath,
        "configPath" to configPath,
        "sizeBytes" to sizeBytes,
        "importedAt" to importedAt,
        "isActive" to isActive,
        "loadDurationMs" to loadDurationMs,
        "validationWarnings" to validationWarnings,
    )
}
