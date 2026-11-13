#include "mnn_prefill_control.hpp"

#include <array>
#include <iostream>
#include <stdexcept>
#include <vector>

struct PositionContext {
    int all_seq_len;
};

// Extracted from the patched upstream Omni::gen_position_ids by the runner.
#include "mnn_prefill_positions.inc"

int main() {
    try {
        int cases = 0;
        const std::array<std::vector<int>, 4> positions{{
            {0, 1, 2, 3, 4, 5},
            {0, 0, 1, 1, 2, 2},
            {0, 1, 0, 1, 0, 1},
            {3, 3, 4, 4, 5, 5},
        }};
        for (bool relativeSpatial : {false, true}) {
            for (int axes : {3, 4}) {
                for (int base : {0, 17}) {
                    mnn_engine::PrefillScope scope(base);
                    auto full = fillPositions(positions, axes, 6, base, relativeSpatial);
                    for (int offset : {0, 2, 4}) {
                        auto chunk = fillPositions(positions, axes, 2, base + offset, relativeSpatial);
                        for (int axis = 0; axis < axes; ++axis) {
                            for (int i = 0; i < 2; ++i) {
                                const int shift = relativeSpatial && axis > 0 ? 0 : base;
                                if (chunk[axis * 2 + i] != positions[axis][offset + i] + shift ||
                                    chunk[axis * 2 + i] != full[axis * 6 + offset + i]) {
                                    throw std::runtime_error("Chunked mRoPE changed an absolute or relative axis");
                                }
                            }
                        }
                    }
                    ++cases;
                }
            }
        }
        {
            mnn_engine::PrefillScope scope(17);
            auto fallback = fillPositions({}, 4, 2, 21, false);
            for (int axis = 0; axis < 4; ++axis) {
                if (fallback[axis * 2] != 21 || fallback[axis * 2 + 1] != 22) {
                    throw std::runtime_error("Missing position axes must use the full sequence offset");
                }
            }
            ++cases;
        }
        if (mnn_engine::prefillBasePosition != 0) {
            throw std::runtime_error("Prefill position scope leaked into the next request");
        }
        std::cout << cases << " multimodal prefill position regression cases passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
