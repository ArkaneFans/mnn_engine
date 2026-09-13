# Native regression tests

Run in Linux/WSL with the pinned MNN submodule initialized:

```bash
bash scripts/test_mnn_llm_adapter.sh "$PWD"
bash scripts/test_mnn_tokenizer.sh "$PWD"
```

The first command checks the model-format guard, incremental generation and
failure handling, bounded native log storage, and the real MNN log macros with
Android logging suppressed. LLM execution is stubbed; this does not measure
device inference or GPU/NPU numerical accuracy.

The tokenizer test builds the upstream tokenizer with the same compatibility
patch used in `libMNN.so`, then checks single-token decoding against a generated
MTOK fixture, including thinking/tool markers and Unicode added tokens. It does
not download a model. Both commands write only under `.native/tests/`.

One-off Hexagon device investigation tools are outside the maintained test
suite. The findings and reproduction conditions remain in
`doc/HEXAGON_8_ELITE_INVESTIGATION_ZH.md`.
