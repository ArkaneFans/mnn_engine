package com.arkanefans.mnn_engine.runtime

import com.arkanefans.mnn_engine.MnnEngineOperationException

enum class MnnBackend(val wireName: String) {
    CPU("cpu"), OPENCL("opencl"), VULKAN("vulkan"), HEXAGON("hexagon");
}

data class MnnLoadOptions(val backend: MnnBackend = MnnBackend.CPU) {
    companion object {
        fun fromMap(options: Map<*, *>?): MnnLoadOptions {
            val value = options?.get("backend") ?: return MnnLoadOptions()
            val backend = MnnBackend.entries.firstOrNull { it.wireName == value }
                ?: throw MnnEngineOperationException("invalid_backend", "Unsupported MNN backend: $value")
            return MnnLoadOptions(backend)
        }
    }
}
