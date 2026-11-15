#pragma once

// A controllable LLM boundary for the adapter's error/streaming regression
// tests. Actual MNN headers and ABI are verified by the Android native build.
#include <cstdint>
#include <functional>
#include <ostream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>
#include "nlohmann/json.hpp"

namespace MNN::Transformer { struct LlmContext; }
namespace mnn_engine { bool cancelPrefill(MNN::Transformer::LlmContext*); }

namespace MNN::Transformer {
using ChatMessages = std::vector<std::pair<std::string, std::string>>;
enum class LlmStatus {
    NOT_LOADED = -1, RUNNING = 0, NORMAL_FINISHED = 1, MAX_TOKENS_FINISHED = 2,
    USER_CANCEL = 3, INTERNAL_ERROR = 4, TIMEOUT = 5,
};
struct LlmContext {
    LlmStatus status = LlmStatus::NOT_LOADED;
    int prompt_len = 0;
    int gen_seq_len = 0;
    int all_seq_len = 0;
    int64_t prefill_us = 0;
    int64_t decode_us = 0;
    int64_t sample_us = 0;
    std::vector<int> output_tokens;
    std::vector<int> history_tokens;
    std::mutex mutex;
};
struct FakeLlmBehavior {
    LlmStatus loadStatus = LlmStatus::RUNNING;
    LlmStatus prefillStatus = LlmStatus::RUNNING;
    LlmStatus decodeStatus = LlmStatus::MAX_TOKENS_FINISHED;
    bool advanceToken = true;
    bool emitEndMarker = false;
    bool corruptHistory = false;
    bool unsyncedKv = false;
    bool linearReplay = false;
    int officialCachedTokens = 0;
    int messageResponseCalls = 0;
    int resetCalls = 0;
    int replayCalls = 0;
    int prefillCalls = 0;
    int decodeCalls = 0;
    int loadCalls = 0;
    int prefillBatches = 1;
    int processedBatches = 0;
    std::function<void(int)> onPrefillBatch;
    std::string modelConfig = "{}";
    std::string effectiveConfig = "{}";
    std::string tokenText = "generated text ";
    std::vector<int> inputTokens;
    std::vector<std::vector<int>> prefillInputs;
    std::vector<std::vector<int>> retainedPrefixes;
    std::vector<ChatMessages> syncedMessages;
    std::vector<size_t> erasedHistory;
};
inline FakeLlmBehavior fakeLlm;

class Llm {
public:
    static Llm* createLLM(const std::string&) { return new Llm; }
    static void destroy(Llm* llm) { delete llm; }
    bool set_config(const std::string& config) {
        mergeConfig(config_, nlohmann::json::parse(config));
        fakeLlm.effectiveConfig = config_.dump();
        return true;
    }
    std::string dump_config() { return config_.dump(); }
    bool load() {
        ++fakeLlm.loadCalls;
        context_.status = fakeLlm.loadStatus;
        return true;
    }
    const LlmContext* getContext() const { return &context_; }
    void reset() {
        ++fakeLlm.resetCalls;
        // As in upstream reset(), terminal/error status is not cleared here.
        context_.gen_seq_len = 0;
        context_.all_seq_len = 0;
        context_.output_tokens.clear();
        context_.history_tokens.clear();
        keepKv_ = 0;
        kvTokens_.clear();
        cacheReady_ = false;
    }
    std::string apply_chat_template(const ChatMessages& messages) const {
        std::string prompt;
        for (const auto& message : messages) prompt += message.first + ":" + message.second;
        return prompt;
    }
    std::vector<int> tokenizer_encode(const std::string&) {
        if (!fakeLlm.inputTokens.empty()) return fakeLlm.inputTokens;
        std::vector<int> tokens(7 * fakeLlm.prefillBatches);
        for (size_t i = 0; i < tokens.size(); ++i) tokens[i] = static_cast<int>(i + 1);
        return tokens;
    }
    size_t getCurrentHistory() const { return kvTokens_.size() + (fakeLlm.unsyncedKv ? 1 : 0); }
    void eraseHistory(size_t begin, size_t) {
        fakeLlm.erasedHistory.push_back(begin);
        if (fakeLlm.linearReplay) ++fakeLlm.replayCalls;
        keepKv_ = begin;
        context_.all_seq_len = static_cast<int>(begin);
        context_.history_tokens.resize(begin);
    }
    void syncPromptCache(const ChatMessages& messages) {
        if (!config_.value("prompt_cache", false)) return;
        fakeLlm.syncedMessages.push_back(messages);
        cacheReady_ = true;
    }
    void response(const ChatMessages& messages, std::ostream* output, const char* end, int maxTokens) {
        ++fakeLlm.messageResponseCalls;
        auto tokens = tokenizer_encode(apply_chat_template(messages));
        const int cached = config_.value("prompt_cache", false) && cacheReady_
                ? fakeLlm.officialCachedTokens : 0;
        if (cached > 0) {
            tokens.erase(tokens.begin(), tokens.begin() + cached);
        } else {
            context_.all_seq_len = 0;
            context_.history_tokens.clear();
            keepKv_ = 0;
        }
        response(tokens, output, end, maxTokens);
        cacheReady_ = config_.value("prompt_cache", false);
    }
    void response(const std::vector<int>& tokens, std::ostream* output, const char* end, int maxTokens) {
        if (maxTokens != 0) throw std::logic_error("Expected prefill-only response");
        if (keepKv_ < kvTokens_.size()) kvTokens_.resize(keepKv_);
        fakeLlm.retainedPrefixes.push_back(kvTokens_);
        fakeLlm.prefillInputs.push_back(tokens);
        context_.gen_seq_len = 0;
        context_.output_tokens.clear();
        context_.history_tokens.insert(context_.history_tokens.end(), tokens.begin(), tokens.end());
        ++fakeLlm.prefillCalls;
        output_ = output;
        endMarker_ = end;
        context_.prompt_len = static_cast<int>(tokens.size());
        context_.prefill_us = 0;
        context_.status = LlmStatus::RUNNING;
        for (int i = 0; i < fakeLlm.prefillBatches; ++i) {
            if (cancelPrefill()) return;
            ++fakeLlm.processedBatches;
            const size_t begin = tokens.size() * i / fakeLlm.prefillBatches;
            const size_t end = tokens.size() * (i + 1) / fakeLlm.prefillBatches;
            kvTokens_.insert(kvTokens_.end(), tokens.begin() + begin, tokens.begin() + end);
            keepKv_ = kvTokens_.size();
            context_.all_seq_len += static_cast<int>(end - begin);
            context_.prefill_us += 10;
            if (fakeLlm.onPrefillBatch) fakeLlm.onPrefillBatch(i);
            if (fakeLlm.corruptHistory && !context_.history_tokens.empty()) context_.history_tokens[0] = 99;
            context_.status = fakeLlm.prefillStatus;
            if (cancelPrefill()) return;
        }
    }
    void generate(int maxTokens) {
        if (maxTokens != 1) throw std::logic_error("Expected one-token step");
        ++fakeLlm.decodeCalls;
        if (fakeLlm.advanceToken) {
            ++context_.gen_seq_len;
            context_.output_tokens.push_back(42);
            context_.history_tokens.push_back(42);
            if (fakeLlm.decodeStatus != LlmStatus::NORMAL_FINISHED) {
                kvTokens_.push_back(42);
                keepKv_ = kvTokens_.size();
                ++context_.all_seq_len;
                *output_ << fakeLlm.tokenText << std::flush;
            }
        }
        context_.status = fakeLlm.decodeStatus;
        if (fakeLlm.emitEndMarker) *output_ << endMarker_ << std::flush;
    }
private:
    static void mergeConfig(nlohmann::json& target, const nlohmann::json& source) {
        if (!target.is_object() || !source.is_object()) return;
        for (auto it = source.begin(); it != source.end(); ++it) {
            if (target.contains(it.key()) && target[it.key()].is_object() && it.value().is_object()) {
                mergeConfig(target[it.key()], it.value());
            } else {
                target[it.key()] = it.value();
            }
        }
    }

    bool cancelPrefill() { return mnn_engine::cancelPrefill(&context_); }
    nlohmann::json config_ = nlohmann::json::parse(fakeLlm.modelConfig);
    LlmContext context_;
    std::ostream* output_ = nullptr;
    std::string endMarker_;
    std::vector<int> kvTokens_;
    size_t keepKv_ = 0;
    bool cacheReady_ = false;
};
}  // namespace MNN::Transformer
