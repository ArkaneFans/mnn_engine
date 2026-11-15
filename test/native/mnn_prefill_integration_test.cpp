#include "mnn_prefill_control.hpp"
#include "MNN/MNNForwardType.h"

#include <filesystem>
#include <algorithm>
#include <iostream>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#ifdef MNN_ENGINE_REAL_ADAPTER_TEST
#include "mnn_llm_session_adapter.hpp"
#include "mnn_backend_support.hpp"
#include "mnn_hexagon_model_check.hpp"
#include "nlohmann/json.hpp"

MnnBackendCapability probeMnnBackend(const std::string& backend) {
    return {backend, true, true, "available", "real-model regression with preselected backend"};
}
#endif

using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;

namespace {
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

#ifdef MNN_ENGINE_REAL_ADAPTER_TEST
void checkAdapterKv(const std::string& configPath, const std::string& backend = "cpu") {
    using nlohmann::json;
    std::cout << "Running real adapter official prompt-cache regression" << std::endl;
    const bool linear = inspectMnnHexagonModel(configPath, "{}").hasLinearAttention;
    require(backend == "cpu" || backend == "opencl" || backend == "vulkan", "Unsupported adapter test backend");
    const std::string cacheDirectory = ".mnn-official-cache-test-" + backend;
    std::filesystem::create_directories(cacheDirectory);
    const json config = {{"backend_type", backend}, {"thread_num", backend == "cpu" ? 2 : MNN_GPU_TUNING_NONE},
        {"tmp_path", cacheDirectory}, {"sampler_type", "greedy"},
        {"precision", "high"}, {"memory", "normal"}, {"async", false}, {"use_mmap", false},
        {"enable_debug", false}, {"chunk", 128}, {"reuse_kv", true}, {"prompt_cache", true},
        {"jinja", {{"context", {{"enable_thinking", false}}}}}};
    struct Case {
        Case(json input, bool reuse = true, bool think = false, bool stop = false)
            : messages(std::move(input)), cache(reuse), thinking(think), cancel(stop) {}
        json messages;
        bool cache = true;
        bool thinking = false;
        bool cancel = false;
        std::string expected;
        int cached = 0;
    };
    std::vector<Case> cases;
    std::string system = "Follow the user's instructions. ";
    for (int i = 0; i < 24; ++i) system += "This is an official prompt cache correctness test. ";
    const json initial = json::array({{{"role", "system"}, {"content", system}},
        {{"role", "user"}, {"content", "Name the capital city of France. Answer briefly."}}});
    {
        std::unique_ptr<Llm, decltype(&Llm::destroy)> official(Llm::createLLM(configPath), Llm::destroy);
        require(official != nullptr && official->set_config(config.dump()) && official->load(),
                "Could not load the official reference");
        const auto reference = [&](Case test) {
            auto* state = const_cast<MNN::Transformer::LlmContext*>(official->getContext());
            state->status = LlmStatus::RUNNING;
            if (!test.cache) official->reset();
            official->set_config(R"({"jinja":{"context":null}})");
            const json request = {{"reuse_kv", true}, {"prompt_cache", test.cache},
                {"jinja", {{"context", {{"enable_thinking", test.thinking}, {"tools", json::array()}}}}}};
            official->set_config(request.dump());
            MNN::Transformer::ChatMessages messages;
            for (const auto& item : test.messages) messages.emplace_back(item["role"], item["content"]);
            const auto promptSize = official->tokenizer_encode(official->apply_chat_template(messages)).size();
            std::ostringstream output;
            official->response(messages, &output, "<eop>", 0);
            test.cached = test.cache ? std::max(0, static_cast<int>(promptSize) - state->prompt_len) : 0;
            const size_t kvBeforeDecode = official->getCurrentHistory();
            while (state->gen_seq_len < (test.cancel ? 1 : 12) && output.str().find("<eop>") == std::string::npos) {
                official->generate(1);
                require(state->status != LlmStatus::INTERNAL_ERROR && state->status != LlmStatus::TIMEOUT,
                        "Official reference generation failed");
                if (output.str().find("<eop>") == std::string::npos) state->status = LlmStatus::RUNNING;
            }
            test.expected = output.str();
            const auto eop = test.expected.find("<eop>");
            if (eop != std::string::npos) test.expected.erase(eop);
            if (test.cancel) {
                const size_t previous = official->getCurrentHistory();
                state->history_tokens.resize(previous);
                if (previous > kvBeforeDecode) official->eraseHistory(kvBeforeDecode, 0);
            } else if (!test.expected.empty()) {
                messages.emplace_back("assistant", test.expected);
            }
            if (test.cache) official->syncPromptCache(messages);
            cases.push_back(test);
            return test.expected;
        };
        const auto first = reference({initial});
        require(!first.empty(), "Official reference returned no initial answer");
        auto continuation = initial;
        continuation.push_back({{"role", "assistant"}, {"content", first}});
        continuation.push_back({{"role", "user"}, {"content", "What country is that city in? Answer briefly."}});
        const auto second = reference({continuation});
        require(cases.back().cached > 128, "Official continuation must reuse a substantial prefix for both attention types");
        auto third = continuation;
        third.push_back({{"role", "assistant"}, {"content", second}});
        third.push_back({{"role", "user"}, {"content", "Name that country's continent. Answer briefly."}});
        reference({third});
        require(cases.back().cached > 128, "A second append must retain official prompt reuse");
        reference({third});
        require(cases.back().cached == 0, "A retry omitting the last reply must follow official full-history fallback");
        auto edited = third;
        edited[1]["content"] = "Name the capital city of Japan. Answer briefly.";
        reference({edited});
        require(cases.back().cached == 0, "Edited official history must re-prefill");
        reference({initial});
        reference({continuation, true, true});
        require(cases.back().cached > 128, "Changing the thinking generation suffix must preserve official history reuse");
        reference({initial});
        reference({continuation, true, false, true});
        reference({continuation});
        require(cases.back().cached > 128, "Official cancellation rollback must retain reusable prompt history");
        reference({continuation, false});
        reference({continuation});
        require(cases.back().cached == 0, "Opt-out must not seed official reuse");
        auto changedSystem = continuation;
        changedSystem[0]["content"] = system + "Always answer in English.";
        reference({changedSystem});
    }
    MnnLlmSessionAdapter adapter(configPath, config.dump());
    std::string error;
    require(adapter.load(&error), error.c_str());
    for (size_t i = 0; i < cases.size(); ++i) {
        const auto& test = cases[i];
        adapter.prepareGeneration();
        std::string text;
        const json request = {{"cache_prompt", test.cache},
            {"jinja", {{"context", {{"enable_thinking", test.thinking}, {"tools", json::array()}}}}}};
        const auto metrics = adapter.generate(test.messages.dump(), request.dump(), 12, [&](const std::string& part) {
            text += part;
            return test.cancel;
        });
        std::cout << "official case=" << i << ", linear=" << linear << ", prompt=" << metrics.promptTokens
                  << ", cached=" << metrics.cachedPromptTokens << ", finish=" << metrics.finishReason << std::endl;
        require(metrics.promptTokens > 128 && metrics.cachedPromptTokens == test.cached,
                "Adapter input usage differs from the official incremental prefill");
        if (test.cancel) require(metrics.finishReason == "cancelled", "Expected adapter cancellation");
        else require(text == test.expected, "Adapter greedy output differs from the official stateful conversation");
    }
    std::cout << "Real adapter KV regression passed: backend=" << backend << ", linear=" << linear
              << ", official cases=" << cases.size()
              << ", repeated append, thinking, history changes, cancellation replay, opt-out" << std::endl;
}
#endif

struct Cancellation {
    const MNN::Transformer::LlmContext* context;
    int afterTokens;
};

std::vector<int> run(Llm& llm, const std::string& prompt, int chunk, int cancelAfter, int maxTokens) {
    llm.reset();
    // Match the adapter's terminal-state transition. Upstream reset() only
    // clears KV/history; it deliberately leaves USER_CANCEL/error status alone.
    auto* state = const_cast<MNN::Transformer::LlmContext*>(llm.getContext());
    if (state->status == LlmStatus::NORMAL_FINISHED || state->status == LlmStatus::MAX_TOKENS_FINISHED ||
        state->status == LlmStatus::USER_CANCEL) {
        state->status = LlmStatus::RUNNING;
    }
    llm.set_config("{\"chunk\":" + std::to_string(chunk) + "}");
    Cancellation cancellation{llm.getContext(), cancelAfter};
    mnn_engine_set_prefill_cancel_callback([](void* data) {
        const auto& request = *static_cast<Cancellation*>(data);
        return request.afterTokens >= 0 && request.context->all_seq_len >= request.afterTokens;
    }, &cancellation);
    std::ostringstream output;
    llm.response(MNN::Transformer::ChatMessages{{"user", prompt}}, &output, "<eop>", maxTokens);
    mnn_engine_set_prefill_cancel_callback(nullptr, nullptr);
    const auto* context = llm.getContext();
    if (cancelAfter >= 0) {
        require(context->status == LlmStatus::USER_CANCEL, "Expected normal USER_CANCEL status");
        require(context->gen_seq_len == 0 && output.str().empty(), "Cancelled prefill must not decode");
        require(context->all_seq_len <= cancelAfter, "Cancellation ran past the requested chunk boundary");
    } else {
        require(context->status == LlmStatus::MAX_TOKENS_FINISHED ||
                    context->status == LlmStatus::NORMAL_FINISHED || context->status == LlmStatus::RUNNING,
                "Generation left an error status");
        require(context->gen_seq_len > 0, "Expected decoded tokens");
    }
    std::cout << "chunk=" << chunk << ", cancel_after=" << cancelAfter
              << ", prompt_tokens=" << context->prompt_len
              << ", processed_tokens=" << context->all_seq_len
              << ", generated_tokens=" << context->gen_seq_len
              << ", prefill_us=" << context->prefill_us << std::endl;
    return context->output_tokens;
}
}

int main(int argc, char** argv) {
    if (argc < 2 || argc > 6) {
        std::cerr << "Usage: mnn_prefill_integration_test /path/to/model/config.json [cpu|opencl|vulkan] [image_path] [--chunked-only] [--resume-prefill]\n";
        return 2;
    }
    try {
#ifdef MNN_ENGINE_REAL_ADAPTER_TEST
        if ((argc == 3 || argc == 4) && std::string(argv[2]) == "--adapter-only") {
            checkAdapterKv(argv[1], argc == 4 ? argv[3] : "cpu");
            return 0;
        }
#endif
        const std::string backend = argc >= 3 ? argv[2] : "cpu";
        std::string imagePath;
        bool compareChunkModes = true;
        bool checkPrefillResume = false;
        for (int i = 3; i < argc; ++i) {
            if (std::string(argv[i]) == "--chunked-only") compareChunkModes = false;
            else if (std::string(argv[i]) == "--resume-prefill") checkPrefillResume = true;
            else imagePath = argv[i];
        }
        const bool hasImage = !imagePath.empty();
        require(backend == "cpu" || backend == "opencl" || backend == "vulkan", "Unsupported test backend");
        std::unique_ptr<Llm, decltype(&Llm::destroy)> llm(Llm::createLLM(argv[1]), Llm::destroy);
        require(llm != nullptr, "Could not create model");
        // On GPU this field is a tuning bitmask, not a CPU thread count.
        // Disable tuning in the correctness fixture; app defaults are tested separately.
        const int threads = backend == "cpu" ? 2 : MNN_GPU_TUNING_NONE;
        llm->set_config("{\"backend_type\":\"" + backend + "\",\"thread_num\":" + std::to_string(threads) +
            R"(,"sampler_type":"greedy","async":false,"use_mmap":false,"enable_debug":false})");
        if (backend != "cpu") {
            // OpenCL and Vulkan cache formats differ, just as in the app runtime.
            const auto cacheDirectory = ".mnn-prefill-test-" + backend;
            std::filesystem::create_directories(cacheDirectory);
            llm->set_config("{\"tmp_path\":\"" + cacheDirectory + "\"}");
        }
        require(llm->load(), "Could not load model");
        const int referenceChunk = compareChunkModes ? 0 : 128;
        std::cout << "Testing backend=" << backend << ", reference_chunk=" << referenceChunk << std::endl;
        const std::string shortPrompt = hasImage
            ? "Name the fruit and its color. Answer briefly. <img>" + imagePath + "</img>"
            : "Reply with the word hello.";
        std::string longPrompt;
        for (int i = 0; i < 48; ++i) longPrompt += "one two three four five six seven eight. ";
        longPrompt += shortPrompt;

        const auto generate = [&](const std::string& prompt, int chunk, int cancelAfter = -1) {
            return run(*llm, prompt, chunk, cancelAfter, hasImage ? 32 : 4);
        };
        const auto sameTokens = [&](const std::vector<int>& actual, const std::vector<int>& expected, const char* message) {
            if (actual == expected) return;
            std::cerr << "Expected: ";
            for (int token : expected) std::cerr << llm->tokenizer_decode(token);
            std::cerr << "\nActual: ";
            for (int token : actual) std::cerr << llm->tokenizer_decode(token);
            std::cerr << '\n';
            throw std::runtime_error(message);
        };
        const auto shortBaseline = generate(shortPrompt, referenceChunk);
        if (hasImage) {
            std::cout << "Image baseline: ";
            for (int token : shortBaseline) std::cout << llm->tokenizer_decode(token);
            std::cout << std::endl;
            sameTokens(generate(shortPrompt, referenceChunk), shortBaseline, "Repeated image changed with the same config");
        }
        const auto shortChunked = generate(shortPrompt, 128);
        sameTokens(shortChunked, shortBaseline, "Short prompt output mismatch");
        const auto longBaseline = generate(longPrompt, referenceChunk);
        const int promptTokens = llm->getContext()->prompt_len;
        require(promptTokens > 256, "Fixture must span multiple chunks");
        sameTokens(generate(longPrompt, 128), longBaseline, "Long prompt output mismatch");
        require(llm->getContext()->prompt_len == promptTokens, "Chunking changed the reported prompt length");

        generate(longPrompt, 128, 0);
        sameTokens(generate(shortPrompt, 128), shortChunked, "Early cancellation poisoned the next request");
        generate(longPrompt, 128, 128);
        require(llm->getContext()->all_seq_len == 128, "Expected exactly one completed prefill chunk");
        if (!hasImage && checkPrefillResume) {
            const auto input = llm->tokenizer_encode(llm->apply_chat_template(MNN::Transformer::ChatMessages{{"user", longPrompt}}));
            auto* state = const_cast<MNN::Transformer::LlmContext*>(llm->getContext());
            const size_t completed = llm->getCurrentHistory();
            require(completed == 128 && completed < input.size(), "KV metadata must identify the completed prefill prefix");
            {
                std::lock_guard<std::mutex> historyLock(state->mutex);
                require(state->history_tokens.size() >= completed &&
                            std::equal(input.begin(), input.begin() + completed, state->history_tokens.begin()),
                        "Completed prefill token history differs from the prompt prefix");
                state->history_tokens.resize(completed);
            }
            state->status = LlmStatus::RUNNING;
            llm->set_config(R"({"reuse_kv":true})");
            std::ostringstream resumedOutput;
            llm->response(std::vector<int>(input.begin() + completed, input.end()), &resumedOutput, "<eop>", 4);
            sameTokens(state->output_tokens, longBaseline, "Resuming cancelled prefill KV differs from full prefill");
            llm->set_config(R"({"reuse_kv":false})");
            std::cout << "Resumed cancelled prefill: " << completed << " cached tokens, identical greedy output" << std::endl;
        }
        sameTokens(generate(shortPrompt, 128), shortChunked, "Partial KV cache survived reset");
        generate(longPrompt, 128, promptTokens);
        sameTokens(generate(shortPrompt, 128), shortChunked, "Last-chunk cancellation poisoned the next request");
        std::cout << "Real MNN prefill regression passed: backend=" << backend << ", " << promptTokens
                  << " prompt tokens, " << (compareChunkModes ? "chunked/unchunked" : "repeatable chunked")
                  << " greedy output, cancellation at 0/128/final token, reuse\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAILED: " << error.what() << '\n';
        return 1;
    }
}
