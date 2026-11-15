#pragma once

#include <cstddef>
#include <string>

struct MnnHexagonModelInfo {
    size_t nonC4AttentionOps = 0;
    std::string firstNonC4Attention;
    bool hasKvCache = false;
    bool hasLinearAttention = false;
    bool hasUncachedAttention = false;
};

// Inspect graph metadata only; never load/dequantize the external weights or
// open a DSP session. effectiveConfig is Llm::dump_config() after overrides.
MnnHexagonModelInfo inspectMnnHexagonModel(const std::string& configPath, const std::string& effectiveConfig);
