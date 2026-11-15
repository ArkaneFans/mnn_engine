#include "mnn_llm_session_adapter.hpp"
#include "mnn_backend_support.hpp"
#include "mnn_hexagon_model_check.hpp"
#include "mnn_prefill_control.hpp"

#include <iostream>
#include <stdexcept>
#include <thread>
#include <tuple>

using MNN::Transformer::fakeLlm;
using MNN::Transformer::LlmStatus;

MnnBackendCapability probeMnnBackend(const std::string& backend) {
    return {backend, true, true, "available", ""};
}

MnnHexagonModelInfo fakeHexagonModel;
MnnHexagonModelInfo inspectMnnHexagonModel(const std::string&, const std::string&) {
    return fakeHexagonModel;
}

namespace {
constexpr auto messages = R"([{"role":"user","content":"hello"}])";

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void load(MnnLlmSessionAdapter& adapter) {
    std::string error;
    require(adapter.load(&error), error.c_str());
}

void expectFailure(MnnLlmSessionAdapter& adapter, int budget, const std::string& stage, const std::string& status) {
    try {
        adapter.generate(messages, "{}", budget, [](const auto&) { return false; });
    } catch (const std::runtime_error& error) {
        const std::string message = error.what();
        require(message.find(stage) != std::string::npos, "Missing failure stage");
        require(message.find(status) != std::string::npos, "Missing runtime status");
        require(message.find("backend=hexagon") != std::string::npos, "Missing selected backend");
        return;
    }
    throw std::runtime_error("Expected generation to fail");
}
}  // namespace

int main() {
    int cases = 0;
    try {
        {
            fakeLlm = {};
            fakeHexagonModel.hasKvCache = true;
            fakeLlm.tokenText = "A\xf0\x9f";
            MnnLlmSessionAdapter adapter("config.json", "{}");
            load(adapter);
            std::string streamed;
            adapter.generate(messages, "{}", 1, [&](const auto& part) { streamed += part; return false; });
            require(streamed == fakeLlm.tokenText && fakeLlm.syncedMessages.back().back().second == "A",
                    "Truncated UTF-8 must not corrupt the official cache's template JSON");
            fakeHexagonModel = {};
            ++cases;
        }
        {
            fakeLlm = {};
            fakeHexagonModel = {};
            fakeHexagonModel.hasKvCache = true;
            fakeLlm.inputTokens = {1, 2, 3, 4};
            MnnLlmSessionAdapter adapter("config.json", "{}");
            load(adapter);
            adapter.generate(messages, "{}", 1, [](const auto&) { return false; });
            require(fakeLlm.messageResponseCalls == 1 && fakeLlm.syncedMessages.back().size() == 2,
                    "Normal completion must use official message response and sync the assistant history");
            fakeLlm.inputTokens = {1, 2, 3, 4, 5, 6, 7};
            fakeLlm.officialCachedTokens = 3;
            auto metrics = adapter.generate(messages, "{}", 0, [](const auto&) { return false; });
            require(metrics.promptTokens == 7 && metrics.cachedPromptTokens == 3 &&
                        fakeLlm.prefillInputs.back() == std::vector<int>({4, 5, 6, 7}),
                    "Usage must report the full prompt and the actual official incremental prefill");
            require(fakeLlm.syncedMessages.back().size() == 1,
                    "Prefill-only completion must not append an empty assistant");
            metrics = adapter.generate(messages, R"({"cache_prompt":false})", 0, [](const auto&) { return false; });
            require(metrics.cachedPromptTokens == 0 && fakeLlm.retainedPrefixes.back().empty(),
                    "Cache opt-out must clear and bypass official prompt reuse");
            require(adapter.generate(messages, "{}", 0, [](const auto&) { return false; }).cachedPromptTokens == 0,
                    "A disabled request must not seed the next request's prompt cache");
            adapter.reset();
            require(adapter.generate(messages, "{}", 0, [](const auto&) { return false; }).cachedPromptTokens == 0,
                    "Explicit reset must clear official prompt cache");
            fakeHexagonModel = {};
            ++cases;
        }
        for (bool linear : {false, true}) {
            fakeLlm = {};
            fakeHexagonModel.hasKvCache = true;
            fakeHexagonModel.hasLinearAttention = linear;
            fakeLlm.linearReplay = linear;
            fakeLlm.inputTokens = {1, 2, 3, 4};
            MnnLlmSessionAdapter adapter("config.json", "{}");
            load(adapter);
            const auto metrics = adapter.generate(messages, "{}", 8, [](const auto&) { return true; });
            require(metrics.finishReason == "cancelled" && fakeLlm.erasedHistory.back() == 4 &&
                        fakeLlm.replayCalls == (linear ? 1 : 0) && fakeLlm.resetCalls == 0,
                    "Decode cancellation must delegate retained-state rollback to official eraseHistory");
            require(fakeLlm.syncedMessages.back().size() == 1,
                    "Cancelled decode must sync the request history without a partial assistant");
            adapter.prepareGeneration();
            fakeLlm.officialCachedTokens = 2;
            require(adapter.generate(messages, "{}", 0, [](const auto&) { return false; }).cachedPromptTokens == 2,
                    "Official prompt reuse must remain available after ordinary or linear rollback");
            fakeHexagonModel = {};
            ++cases;
        }
        {
            fakeLlm = {};
            fakeHexagonModel.hasKvCache = true;
            MnnLlmSessionAdapter adapter("config.json", "{}");
            load(adapter);
            adapter.generate(messages, "{}", 1, [](const auto&) { return false; });
            adapter.prepareGeneration();
            adapter.cancel();
            const int resets = fakeLlm.resetCalls;
            require(adapter.generate(messages, "{}", 1, [](const auto&) { return false; }).finishReason == "cancelled" &&
                        fakeLlm.resetCalls == resets,
                    "Cancellation before inference must preserve the prior official cache");
            adapter.prepareGeneration();
            fakeLlm.officialCachedTokens = 3;
            require(adapter.generate(messages, "{}", 0, [](const auto&) { return false; }).cachedPromptTokens == 3,
                    "The next prepared request must reuse the preserved official cache");
            adapter.prepareGeneration();
            adapter.cancel();
            adapter.generate(messages, R"({"cache_prompt":false})", 0, [](const auto&) { return false; });
            adapter.prepareGeneration();
            require(adapter.generate(messages, "{}", 0, [](const auto&) { return false; }).cachedPromptTokens == 0,
                    "Cache opt-out must clear even when cancelled before inference");
            fakeHexagonModel = {};
            ++cases;
        }
        for (int cancelAt : {0, 2}) {
            fakeLlm = {};
            fakeHexagonModel.hasKvCache = true;
            fakeLlm.prefillBatches = 3;
            MnnLlmSessionAdapter adapter("config.json", "{}");
            load(adapter);
            fakeLlm.onPrefillBatch = [&](int batch) { if (batch == cancelAt) adapter.cancel(); };
            const auto metrics = adapter.generate(messages, "{}", 8, [](const auto&) { return false; });
            require(metrics.finishReason == "cancelled" && metrics.completionTokens == 0,
                    "Prefill cancellation must remain a normal cancellation");
            require(cancelAt == 0 ? fakeLlm.resetCalls == 1 && fakeLlm.syncedMessages.empty()
                                 : fakeLlm.resetCalls == 0 && fakeLlm.syncedMessages.back().size() == 1,
                    "Only a complete prefill may seed the official complete-history prompt cache");
            fakeLlm.onPrefillBatch = {};
            adapter.prepareGeneration();
            fakeLlm.officialCachedTokens = 14;
            require(adapter.generate(messages, "{}", 0, [](const auto&) { return false; }).cachedPromptTokens ==
                        (cancelAt == 0 ? 0 : 14),
                    "Uncomputed prefill tokens must never be reported as cached");
            fakeHexagonModel = {};
            ++cases;
        }
        {
            fakeLlm = {};
            fakeHexagonModel.hasKvCache = true;
            fakeHexagonModel.hasLinearAttention = true;
            fakeLlm.linearReplay = true;
            MnnLlmSessionAdapter adapter("config.json", "{}");
            load(adapter);
            adapter.generate(messages, "{}", 0, [](const auto&) { return false; });
            fakeLlm.prefillBatches = 3;
            fakeLlm.officialCachedTokens = 3;
            fakeLlm.onPrefillBatch = [&](int batch) { if (batch == 0) adapter.cancel(); };
            adapter.generate(messages, "{}", 8, [](const auto&) { return false; });
            require(fakeLlm.erasedHistory.back() == 7 && fakeLlm.replayCalls == 1 && fakeLlm.resetCalls == 0,
                    "Partial incremental prefill must restore the prior complete state through official replay");
            fakeLlm.onPrefillBatch = {};
            adapter.prepareGeneration();
            require(adapter.generate(messages, "{}", 0, [](const auto&) { return false; }).cachedPromptTokens == 3,
                    "The restored conversation must remain reusable after partial prefill cancellation");
            fakeHexagonModel = {};
            ++cases;
        }
        {
            fakeLlm = {};
            fakeHexagonModel.hasKvCache = true;
            fakeLlm.unsyncedKv = true;
            MnnLlmSessionAdapter adapter("config.json", "{}");
            load(adapter);
            adapter.generate(messages, "{}", 8, [](const auto&) { return true; });
            require(fakeLlm.resetCalls == 1 && fakeLlm.erasedHistory.empty(),
                    "Inconsistent committed lengths must reset instead of replaying unverified history");
            fakeHexagonModel = {};
            ++cases;
        }
        {
            fakeLlm = {};
            fakeHexagonModel.hasKvCache = true;
            MnnLlmSessionAdapter adapter("config.json", "{}");
            load(adapter);
            adapter.generate(messages, "{}", 1, [&](const auto&) { adapter.cancel(); return false; });
            require(fakeLlm.erasedHistory.size() == 1,
                    "Atomic cancellation must use the same official rollback as callback cancellation");
            adapter.prepareGeneration();
            require(adapter.generate(R"([{"role":"user","content":"<img>test.jpg</img>"}])", "{}", 0,
                        [](const auto&) { return false; }).cachedPromptTokens == 0 &&
                        !nlohmann::json::parse(fakeLlm.effectiveConfig)["prompt_cache"].get<bool>(),
                    "Media requests must bypass the text-only official prompt cache");
            fakeLlm.officialCachedTokens = 3;
            require(adapter.generate(messages, "{}", 0, [](const auto&) { return false; }).cachedPromptTokens == 0,
                    "Media state must not seed a later text prompt cache");
            fakeHexagonModel = {};
            ++cases;
        }
        {
            fakeLlm = {};
            fakeHexagonModel = {28, "Attention/Reshape_8_output_0"};
            MnnLlmSessionAdapter adapter("config.json", R"({"backend_type":"hexagon"})");
            std::string error;
            require(!adapter.load(&error), "Reject incompatible attention before allocating model weights");
            require(fakeLlm.loadCalls == 0 && error.find("model_backend_incompatible:") == 0 &&
                        error.find("Transformer C4") != std::string::npos,
                    "Incompatible layout must return a distinct, actionable error before Llm::load");
            ++cases;
        }
        {
            fakeLlm = {};
            MnnLlmSessionAdapter adapter("config.json", R"({"backend_type":"cpu"})");
            load(adapter);
            require(fakeLlm.loadCalls == 1, "Hexagon model restrictions must not block CPU");
            fakeHexagonModel = {};
            ++cases;
        }
        for (auto status : {LlmStatus::INTERNAL_ERROR, LlmStatus::TIMEOUT}) {
            for (int budget : {0, 5}) {
                fakeLlm = {};
                fakeLlm.prefillStatus = status;
                MnnLlmSessionAdapter adapter("config.json", R"({"backend_type":"hexagon"})");
                load(adapter);
                expectFailure(adapter, budget, "stage=prefill",
                        status == LlmStatus::TIMEOUT ? "TIMEOUT(5)" : "INTERNAL_ERROR(4)");
                require(fakeLlm.decodeCalls == 0, "A failed prefill must not enter decode");
                expectFailure(adapter, budget, "stage=request_begin", "reload the model");
                require(fakeLlm.prefillCalls == 1, "A failed session must not silently be reused");
                ++cases;
            }
        }
        for (auto status : {LlmStatus::INTERNAL_ERROR, LlmStatus::TIMEOUT}) {
            fakeLlm = {};
            fakeLlm.decodeStatus = status;
            MnnLlmSessionAdapter adapter("config.json", R"({"backend_type":"hexagon"})");
            load(adapter);
            expectFailure(adapter, 1, "stage=decode", status == LlmStatus::TIMEOUT ? "TIMEOUT(5)" : "INTERNAL_ERROR(4)");
            require(fakeLlm.decodeCalls == 1, "Error at token budget must not be reported as length success");
            ++cases;
        }
        {
            fakeLlm = {};
            fakeLlm.advanceToken = false;
            fakeLlm.decodeStatus = LlmStatus::RUNNING;
            MnnLlmSessionAdapter adapter("config.json", R"({"backend_type":"hexagon"})");
            load(adapter);
            expectFailure(adapter, 5, "stage=decode", "before_generated=0");
            require(fakeLlm.decodeCalls == 1, "No-progress guard must still stop a stalled loop");
            ++cases;
        }
        {
            fakeLlm = {};
            fakeLlm.emitEndMarker = true;
            MnnLlmSessionAdapter adapter("config.json", "{}");
            load(adapter);
            std::string text;
            auto metrics = adapter.generate(messages, "{}", 2, [&](const auto& token) {
                text += token;
                return false;
            });
            require(metrics.completionTokens == 2 && metrics.finishReason == "length", "Stepping must respect budget");
            require(text == "generated text generated text ", "Artificial step end markers must not truncate text");
            metrics = adapter.generate(messages, "{}", 1, [](const auto&) { return false; });
            require(metrics.completionTokens == 1, "A length-finished session must remain reusable");
            ++cases;
        }
        {
            fakeLlm = {};
            fakeLlm.decodeStatus = LlmStatus::NORMAL_FINISHED;
            fakeLlm.emitEndMarker = true;
            MnnLlmSessionAdapter adapter("config.json", "{}");
            load(adapter);
            const auto metrics = adapter.generate(messages, "{}", -1, [](const auto&) { return false; });
            require(metrics.finishReason == "stop" && fakeLlm.decodeCalls == 1, "Natural EOS must end normally");
            ++cases;
        }
        {
            fakeLlm = {};
            MnnLlmSessionAdapter adapter("config.json", "{}");
            load(adapter);
            const auto metrics = adapter.generate(messages, "{}", 20, [](const auto&) { return true; });
            require(metrics.finishReason == "cancelled" && fakeLlm.decodeCalls == 1, "Cancellation must remain supported");
            ++cases;
        }
        {
            fakeLlm = {};
            fakeLlm.loadStatus = LlmStatus::INTERNAL_ERROR;
            MnnLlmSessionAdapter adapter("config.json", "{}");
            std::string error;
            require(!adapter.load(&error), "A loaded model in an error state must not be advertised as ready");
            require(error.find("INTERNAL_ERROR(4)") != std::string::npos, "Load error must include status");
            ++cases;
        }
        {
            fakeLlm = {};
            MnnLlmSessionAdapter adapter("config.json", "{}");
            load(adapter);
            adapter.reset();
            adapter.cancel();
            auto metrics = adapter.generate(messages, "{}", 4, [](const auto&) { return false; });
            require(metrics.finishReason == "cancelled" && metrics.completionTokens == 0 &&
                        fakeLlm.prefillCalls == 0,
                    "Cancellation before native entry must not be cleared or enter prefill");
            adapter.reset();
            metrics = adapter.generate(messages, "{}", 1, [](const auto&) { return false; });
            require(metrics.finishReason == "length" && metrics.completionTokens == 1,
                    "The next prepared request must work after early cancellation");
            ++cases;
        }
        for (int cancelAt : {0, 2}) {
            fakeLlm = {};
            fakeLlm.prefillBatches = 3;
            MnnLlmSessionAdapter adapter("config.json", "{}");
            load(adapter);
            fakeLlm.onPrefillBatch = [&](int batch) {
                if (batch == cancelAt) {
                    std::thread cancelling([&] { adapter.cancel(); });
                    cancelling.join();
                }
            };
            auto metrics = adapter.generate(messages, "{}", 4, [](const auto&) { return false; });
            require(metrics.finishReason == "cancelled" && metrics.completionTokens == 0,
                    "Prefill cancellation must finish normally without decoding");
            require(fakeLlm.processedBatches == cancelAt + 1 && fakeLlm.decodeCalls == 0,
                    "No additional prefill batch or decode may run after cancellation");
            require(metrics.promptTokens == 21 && metrics.prefillUs == 10 * (cancelAt + 1),
                    "Cancellation should retain prompt size and completed prefill time");
            fakeLlm.onPrefillBatch = {};
            adapter.reset();
            metrics = adapter.generate(messages, "{}", 2, [](const auto&) { return false; });
            require(metrics.finishReason == "length" && metrics.completionTokens == 2,
                    "A prefill-cancelled session must work on the next request");
            ++cases;
        }
        {
            fakeLlm = {};
            fakeLlm.prefillBatches = 3;
            fakeLlm.prefillStatus = LlmStatus::INTERNAL_ERROR;
            MnnLlmSessionAdapter adapter("config.json", R"({"backend_type":"hexagon"})");
            load(adapter);
            fakeLlm.onPrefillBatch = [&](int) { adapter.cancel(); };
            expectFailure(adapter, 1, "stage=prefill", "INTERNAL_ERROR(4)");
            require(fakeLlm.processedBatches == 1, "A failing batch must prevent subsequent batches");
            ++cases;
        }
        {
            mnn_engine_set_prefill_cancel_callback([](void*) { return true; }, nullptr);
            MNN::Transformer::LlmContext otherContext;
            otherContext.status = LlmStatus::RUNNING;
            std::thread other([&] { mnn_engine::cancelPrefill(&otherContext); });
            other.join();
            require(otherContext.status == LlmStatus::RUNNING,
                    "Cancellation callbacks must not leak across generation threads");
            mnn_engine_set_prefill_cancel_callback(nullptr, nullptr);
            ++cases;
        }
        {
            for (auto status : {LlmStatus::NORMAL_FINISHED, LlmStatus::MAX_TOKENS_FINISHED}) {
                MNN::Transformer::LlmContext context;
                context.status = status;
                require(!mnn_engine::cancelPrefill(&context) && context.status == status,
                        "Without cancellation, successful terminal states keep upstream semantics");
            }
            ++cases;
        }
        {
            for (const std::string baseContext : {R"({"custom":"keep"})",
                    R"({"custom":"keep","enable_thinking":false})",
                    R"({"custom":"keep","enable_thinking":true})"}) {
                fakeLlm = {};
                fakeLlm.modelConfig = "{\"jinja\":{\"context\":" + baseContext + "}}";
                MnnLlmSessionAdapter adapter("config.json", "{}");
                load(adapter);
                const auto defaults = nlohmann::json::parse(baseContext);
                for (bool enabled : {false, true}) {
                    nlohmann::json request = {{"jinja", {{"context", {
                        {"enable_thinking", enabled}, {"tools", nlohmann::json::array({"clock"})}
                    }}}}};
                    adapter.generate(messages, request.dump(), 1, [](const auto&) { return true; });
                    auto context = nlohmann::json::parse(fakeLlm.effectiveConfig)["jinja"]["context"];
                    require(context["enable_thinking"] == enabled && context["custom"] == "keep" &&
                                context["tools"].size() == 1, "Thinking override must retain model variables and tools");
                    adapter.reset();
                    adapter.generate(messages, R"({"jinja":{"context":{"tools":[]}}})", 1,
                            [](const auto&) { return false; });
                    context = nlohmann::json::parse(fakeLlm.effectiveConfig)["jinja"]["context"];
                    require(context.value("custom", "") == "keep" && context["tools"].empty() &&
                                context.contains("enable_thinking") == defaults.contains("enable_thinking"),
                            "An omitted thinking flag must restore its original presence after cancellation");
                    if (defaults.contains("enable_thinking")) {
                        require(context["enable_thinking"] == defaults["enable_thinking"],
                                "An omitted thinking flag must restore the loaded model default");
                    }
                }
            }
            ++cases;
        }
        {
            const std::vector<std::tuple<std::string, std::string, int>> configs = {
                {"{}", "{}", 128},
                {R"({"attention_mask":"float"})", "{}", 128},
                {R"({"attention_mask":"glm"})", "{}", 0},
                {R"({"attention_mask":"glm2"})", "{}", 0},
                {R"({"attention_mask":"int"})", "{}", 0},
                {R"({"chunk":0})", "{}", 0},
                {R"({"chunk":64,"chunk_limits":[64,1]})", "{}", 64},
                {"{}", R"({"chunk":32})", 32},
                {"{}", R"({"chunk":0})", 0},
            };
            for (const auto& [modelConfig, runtimeConfig, expectedChunk] : configs) {
                fakeLlm = {};
                fakeLlm.modelConfig = modelConfig;
                MnnLlmSessionAdapter adapter("config.json", runtimeConfig);
                load(adapter);
                adapter.generate(messages, "{}", 1, [](const auto&) { return false; });
                const auto effective = nlohmann::json::parse(fakeLlm.effectiveConfig);
                require(effective.value("chunk", 0) == expectedChunk,
                        "Default chunking must respect model mask semantics and explicit settings");
            }
            ++cases;
        }
        std::cout << cases << " native adapter regression cases passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAILED: " << error.what() << '\n';
        return 1;
    }
}
