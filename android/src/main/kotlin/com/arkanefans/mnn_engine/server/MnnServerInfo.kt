package com.arkanefans.mnn_engine.server

data class MnnServerInfo(
    val running: Boolean,
    val host: String,
    val port: Int,
    val startedAt: Long?,
    val startDurationMs: Long?,
) {
    val baseUrl: String
        get() = "http://$host:$port"

    fun toMap(): Map<String, Any?> = mapOf(
        "running" to running,
        "host" to host,
        "port" to port,
        "baseUrl" to baseUrl,
        "startedAt" to startedAt,
        "startDurationMs" to startDurationMs,
    )
}
