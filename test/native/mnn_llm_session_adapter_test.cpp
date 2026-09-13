#include "mnn_llm_session_adapter.hpp"
#include "mnn_backend_support.hpp"
#include "mnn_hexagon_model_check.hpp"

#include <iostream>
#include <stdexcept>

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
        std::cout << cases << " native adapter regression cases passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAILED: " << error.what() << '\n';
        return 1;
    }
}
