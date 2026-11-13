#!/usr/bin/env bash
set -euo pipefail

plugin_root="${1:?Plugin root is required}"
test_dir="${plugin_root}/.native/tests/prefill_positions"
cmake_bin="${MNN_CMAKE:-${HOME}/.local/share/mnn_engine/toolchains/cmake-3.22.1/bin/cmake}"
"${cmake_bin}" -DMNN_PREFILL_SOURCE_DIR="${plugin_root}/MNN/transformers/llm/engine/src" \
    -DMNN_PREFILL_OUTPUT_DIR="${test_dir}" \
    -P "${plugin_root}/scripts/cmake/mnn_prefill_compat.cmake"

# Exercise the actual generated position code, with only its tensor/context
# inputs replaced by arrays. No model download or Android runtime is needed.
python3 - "${test_dir}" <<'PY'
import pathlib
import sys

test_dir = pathlib.Path(sys.argv[1])
source = (test_dir / 'omni.cpp').read_text(encoding='utf-8')
start = source.index('        auto axisValue = [this](int axis, int i) {')
end = source.index('        if (mTalker) {', start)
body = source[start:end].replace('[this]', '[&]')
header = '''std::vector<int> fillPositions(
    const std::array<std::vector<int>, 4>& values,
    int axes, int seq_len, int allSeqLen, bool relativeSpatial) {
    struct { std::vector<int> mT, mH, mW, mX; }
        mPositionIds{values[0], values[1], values[2], values[3]};
    PositionContext context{allSeqLen};
    auto* mContext = &context;
    std::vector<int> result(axes * seq_len);
    auto* ptr = result.data();
'''
(test_dir / 'mnn_prefill_positions.inc').write_text(
    header + body + '    return result;\n}\n', encoding='utf-8')
PY

"${CXX:-g++}" -std=c++17 -Wall -Wextra -Werror \
    -I"${plugin_root}/test/native/stubs" \
    -I"${plugin_root}/MNN/apps/frameworks/3rd_party/include" \
    -I"${plugin_root}/android/src/main/cpp" -I"${test_dir}" \
    "${plugin_root}/android/src/main/cpp/mnn_prefill_control.cpp" \
    "${plugin_root}/test/native/mnn_prefill_positions_test.cpp" \
    -o "${test_dir}/mnn_prefill_positions_test"
"${test_dir}/mnn_prefill_positions_test"
