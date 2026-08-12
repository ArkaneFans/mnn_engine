#include "mnn_llm_session_adapter.hpp"

#include <algorithm>
#include <sstream>
#include <streambuf>
#include <utility>

#include "nlohmann/json.hpp"

using MNN::Transformer::ChatMessages;
using MNN::Transformer::LlmStatus;
using nlohmann::json;

namespace {

constexpr int kNoTokenLimit = -1;

class Utf8StreamProcessor {
public:
    explicit Utf8StreamProcessor(std::function<void(const std::string&)> callback)
        : callback_(std::move(callback)) {}

    void process(const char* data, size_t size) {
        buffer_.append(data, size);
        size_t offset = 0;
        std::string complete;
        while (offset < buffer_.size()) {
            const int length = charLength(static_cast<unsigned char>(buffer_[offset]));
            if (length == 0 || offset + static_cast<size_t>(length) > buffer_.size()) {
                break;
            }
            complete.append(buffer_, offset, static_cast<size_t>(length));
            offset += static_cast<size_t>(length);
        }
        buffer_.erase(0, offset);
        if (!complete.empty()) callback_(complete);
    }

    void flush() {
        if (!buffer_.empty()) {
            callback_(buffer_);
            buffer_.clear();
        }
    }

private:
    static int charLength(unsigned char value) {
        if ((value & 0x80U) == 0) return 1;
        if ((value & 0xE0U) == 0xC0U) return 2;
        if ((value & 0xF0U) == 0xE0U) return 3;
        if ((value & 0xF8U) == 0xF0U) return 4;
        return 0;
    }

    std::string buffer_;
    std::function<void(const std::string&)> callback_;
};

class SteppingStreamBuffer : public std::streambuf {
public:
    explicit SteppingStreamBuffer(const std::function<bool(const std::string&)>& callback)
        : callback_(callback), utf8_([this](const std::string& text) {
              if (!text.empty() && callback_) {
                  stopRequested_ = stopRequested_ || callback_(text);
              }
          }) {}

    bool stopRequested() const { return stopRequested_; }
    bool pendingEop() const { return pendingEop_; }
    bool finished() const { return finished_; }

    void discardPendingEop() { pendingEop_ = false; }

    void finalizePendingEop() {
        if (pendingEop_) {
            pendingEop_ = false;
            finished_ = true;
        }
        if (!pending_.empty()) {
            utf8_.process(pending_.data(), pending_.size());
            pending_.clear();
        }
        utf8_.flush();
    }

protected:
    std::streamsize xsputn(const char* data, std::streamsize size) override {
        pending_.append(data, static_cast<size_t>(size));
        processPending();
        return size;
    }

private:
    void processPending() {
        static const std::string sentinel = "<eop>";
        while (!pending_.empty()) {
            const size_t eop = pending_.find(sentinel);
            if (eop != std::string::npos) {
                if (eop > 0) utf8_.process(pending_.data(), eop);
                pending_.erase(0, eop + sentinel.size());
                pendingEop_ = true;
                continue;
            }
            if (pending_.size() < sentinel.size()) return;
            const size_t safeSize = pending_.size() - (sentinel.size() - 1);
            utf8_.process(pending_.data(), safeSize);
            pending_.erase(0, safeSize);
        }
    }

    const std::function<bool(const std::string&)>& callback_;
    Utf8StreamProcessor utf8_;
    std::string pending_;
    bool stopRequested_ = false;
    bool pendingEop_ = false;
    bool finished_ = false;
};

ChatMessages parseMessages(const std::string& messagesJson) {
    const json root = json::parse(messagesJson);
    if (!root.is_array() || root.empty()) {
        throw std::invalid_argument("messages must be a non-empty array");
    }
    ChatMessages messages;
    messages.reserve(root.size());
    for (const auto& item : root) {
        const std::string role = item.value("role", "");
        if (role.empty()) throw std::invalid_argument("message role is required");
        if (role == "assistant" && item.contains("tool_calls") && item["tool_calls"].is_array()) {
            auto complex = item;
            for (auto& tool_call : complex["tool_calls"]) {
                if (!tool_call.is_object() || !tool_call.contains("function")) {
                    throw std::invalid_argument("assistant tool_calls contains an invalid call");
                }
                auto& function = tool_call["function"];
                if (function.contains("arguments") && function["arguments"].is_string()) {
                    try {
                        auto arguments = json::parse(function["arguments"].get<std::string>());
                        if (!arguments.is_object()) {
                            throw std::invalid_argument("tool_call arguments must contain a JSON object");
                        }
                        function["arguments"] = std::move(arguments);
                    } catch (const std::exception&) {
                        throw std::invalid_argument("tool_call arguments must contain a JSON object");
                    }
                }
            }
            messages.emplace_back("json", complex.dump());
            continue;
        }
        const std::string content = item.contains("content") && !item["content"].is_null()
                ? item["content"].get<std::string>()
                : "";
        messages.emplace_back(role, content);
    }
    return messages;
}

}  // namespace

MnnLlmSessionAdapter::MnnLlmSessionAdapter(std::string configPath, std::string configJson)
    : configPath_(std::move(configPath)), configJson_(std::move(configJson)) {}

MnnLlmSessionAdapter::~MnnLlmSessionAdapter() {
    cancelRequested_.store(true);
    std::lock_guard<std::mutex> lock(generationMutex_);
    if (llm_ != nullptr) {
        MNN::Transformer::Llm::destroy(llm_);
        llm_ = nullptr;
    }
}

bool MnnLlmSessionAdapter::load(std::string* errorMessage) {
    llm_ = MNN::Transformer::Llm::createLLM(configPath_);
    if (llm_ == nullptr) {
        if (errorMessage != nullptr) *errorMessage = "createLLM failed for " + configPath_;
        return false;
    }
    if (!configJson_.empty() && !llm_->set_config(configJson_)) {
        if (errorMessage != nullptr) *errorMessage = "MNN rejected the runtime config";
        return false;
    }
    if (!llm_->load()) {
        if (errorMessage != nullptr) *errorMessage = "MNN model load returned false";
        return false;
    }
    return true;
}

MnnLlmSessionAdapter::Metrics MnnLlmSessionAdapter::generate(
        const std::string& messagesJson,
        const std::string& requestConfigJson,
        int maxTokens,
        const std::function<bool(const std::string&)>& onToken) {
    std::lock_guard<std::mutex> lock(generationMutex_);
    if (llm_ == nullptr) throw std::runtime_error("Model is not loaded");
    if (!requestConfigJson.empty() && !llm_->set_config(requestConfigJson)) {
        throw std::invalid_argument("MNN rejected request config");
    }
    if (maxTokens < kNoTokenLimit) {
        throw std::invalid_argument("maxTokens must be -1 or greater");
    }
    const ChatMessages messages = parseMessages(messagesJson);
    cancelRequested_.store(false);
    llm_->reset();
    restoreRunningStatusIfTerminal();

    SteppingStreamBuffer streamBuffer(onToken);
    std::ostream output(&streamBuffer);
    int generated = 0;
    const auto hasTokenBudget = [&]() {
        return maxTokens == kNoTokenLimit || generated < maxTokens;
    };
    llm_->response(messages, &output, "<eop>", 0);

    auto resolveStep = [&]() {
        auto* context = const_cast<MNN::Transformer::LlmContext*>(llm_->getContext());
        if (context == nullptr) return;
        const bool cancelled = cancelRequested_.load() || streamBuffer.stopRequested();
        if (context->status == LlmStatus::MAX_TOKENS_FINISHED && !cancelled && hasTokenBudget()) {
            context->status = LlmStatus::RUNNING;
            streamBuffer.discardPendingEop();
            return;
        }
        if (context->status == LlmStatus::NORMAL_FINISHED && !streamBuffer.pendingEop() &&
            !cancelled && hasTokenBudget()) {
            context->status = LlmStatus::RUNNING;
            return;
        }
        if (streamBuffer.pendingEop()) streamBuffer.finalizePendingEop();
    };

    resolveStep();
    while (!cancelRequested_.load() && !streamBuffer.stopRequested() &&
           !streamBuffer.finished() && hasTokenBudget()) {
        const auto* beforeContext = llm_->getContext();
        const int beforeGenerated = beforeContext == nullptr ? generated : beforeContext->gen_seq_len;
        llm_->generate(1);
        const auto* afterContext = llm_->getContext();
        if (afterContext == nullptr) throw std::runtime_error("MNN generation context is unavailable");
        generated = afterContext->gen_seq_len;
        resolveStep();
        if (!cancelRequested_.load() && !streamBuffer.stopRequested() &&
            !streamBuffer.finished() && hasTokenBudget() && generated <= beforeGenerated) {
            throw std::runtime_error("MNN generation stopped before reaching an end marker");
        }
    }
    streamBuffer.finalizePendingEop();

    Metrics metrics;
    const auto* context = llm_->getContext();
    if (context != nullptr) {
        metrics.promptTokens = context->prompt_len;
        metrics.completionTokens = context->gen_seq_len;
        metrics.prefillUs = context->prefill_us;
        metrics.decodeUs = context->decode_us;
        metrics.sampleUs = context->sample_us;
    }
    if (cancelRequested_.load() || streamBuffer.stopRequested()) {
        metrics.finishReason = "cancelled";
    } else if (maxTokens != kNoTokenLimit && generated >= maxTokens &&
               (context == nullptr || context->status != LlmStatus::NORMAL_FINISHED)) {
        metrics.finishReason = "length";
    }
    return metrics;
}

void MnnLlmSessionAdapter::cancel() {
    cancelRequested_.store(true);
}

void MnnLlmSessionAdapter::reset() {
    std::lock_guard<std::mutex> lock(generationMutex_);
    if (llm_ != nullptr) llm_->reset();
}

void MnnLlmSessionAdapter::restoreRunningStatusIfTerminal() {
    auto* context = llm_ == nullptr
        ? nullptr
        : const_cast<MNN::Transformer::LlmContext*>(llm_->getContext());
    if (context != nullptr &&
        (context->status == LlmStatus::MAX_TOKENS_FINISHED ||
         context->status == LlmStatus::NORMAL_FINISHED ||
         context->status == LlmStatus::USER_CANCEL)) {
        context->status = LlmStatus::RUNNING;
    }
}
