# mnn_engine

[English](README.md) | [简体中文](README_ZH.md)

[![pub package](https://img.shields.io/pub/v/mnn_engine.svg)](https://pub.dev/packages/mnn_engine)
[![Build Android native libraries](https://github.com/ArkaneFans/mnn_engine/actions/workflows/build-native-android.yml/badge.svg)](https://github.com/ArkaneFans/mnn_engine/actions/workflows/build-native-android.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Run [Alibaba MNN](https://github.com/alibaba/MNN) large language models on
Android from Flutter, then expose the active model through an on-device
OpenAI-compatible HTTP API.

`mnn_engine` bundles verified `arm64-v8a` native libraries. Applications that
depend on the pub.dev package do not need MNN source code, CMake, the Android
NDK, Linux, or WSL to build.

> [!IMPORTANT]
> This is an independent community plugin and is not an official Alibaba MNN
> Flutter package. The public API is still evolving under the `0.x` version
> series.

## Features

- Imports complete MNN model directories through Android Storage Access
  Framework or from app-owned private storage.
- Validates `config.json` and all referenced model, weight, embedding, and
  tokenizer files before activation.
- Loads, unloads, lists, renames, and deletes imported models. Each model
  directory name is also its runtime and API model ID.
- Keeps model and server state in an Android foreground service.
- Provides runtime snapshots, state events, log snapshots, and live log events.
- Runs a Ktor CIO server on loopback or all available IPv4 interfaces.
- Supports optional Bearer authentication in both bind modes.
- Implements OpenAI-compatible `/v1/models` and `/v1/chat/completions` APIs.
- Supports streaming SSE responses, vision input, function tools, reasoning
  content, and cancellation of the active generation.
- Ships native libraries validated for AArch64, JNI exports, ELF dependencies,
  and Android 16 KB page compatibility.
- Selects CPU, OpenCL or Vulkan for LLM inference, with device capability checks.
- Supports an experimental Hexagon backend when matching SDK-built runtime assets are packaged.

## Platform support

| Requirement | Supported value |
| --- | --- |
| Flutter platform | Android only |
| Android ABI | `arm64-v8a` only |
| Minimum Android version | API 28 |
| Compile SDK used by the plugin | 35 |
| Flutter | 3.35.0 or newer |
| MNN | 3.6.1 at commit `d407447ed56c4121a11ccbd266dc184ca1ead0c2` |
| Inference backend | CPU (default), OpenCL, Vulkan buffer; optional Hexagon |
| Active models | One at a time |
| Concurrent generations | One at a time |

iOS, desktop platforms, `armeabi-v7a`, `x86`, and `x86_64` are not currently
supported.

## Installation

Add the package to your Flutter application:

```yaml
dependencies:
  mnn_engine: ^0.1.0
```

Then run:

```shell
flutter pub get
```

Configure the host Android application for API 28 and ARM64. For Kotlin DSL
(`android/app/build.gradle.kts`):

```kotlin
android {
    defaultConfig {
        minSdk = 28

        ndk {
            abiFilters += "arm64-v8a"
        }
    }
}
```

For Groovy (`android/app/build.gradle`):

```groovy
android {
    defaultConfig {
        minSdk 28

        ndk {
            abiFilters "arm64-v8a"
        }
    }
}
```

The plugin manifest contributes Internet, foreground service, data-sync
foreground service, and notification permissions. On Android 13 and newer,
the host application should request notification permission as part of its own
UI flow.

## Quick start

```dart
import 'package:mnn_engine/mnn_engine.dart';

final engine = MnnEngine.instance;

final eventSubscription = engine.events.listen((event) {
  final snapshot = event.snapshot;
  print(
    'model=${snapshot.modelState}, '
    'server=${snapshot.serverState}, '
    'generation=${snapshot.generationState}',
  );
});

final logSubscription = engine.logs.listen((entry) {
  print('[${entry.level}] ${entry.tag}: ${entry.message}');
});

final engineInfo = await engine.initialize();
if (!engineInfo.nativeLibraryLoaded) {
  throw StateError('MNN native runtime is unavailable.');
}

// Opens the Android directory picker and imports the selected model directory
// into the application's private storage.
final importedModel = await engine.importModelDirectory();
await engine.loadModel(importedModel.modelId);

late final MnnServerInfo server;
try {
  server = await engine.startServer(
    port: 8081,
    apiKey: 'replace-with-a-runtime-secret',
  );
} on MnnEngineException catch (error) {
  if (error.code == 'port_in_use') {
    throw StateError('Port 8081 is currently unavailable.');
  }
  rethrow;
}
print('OpenAI-compatible base URL: ${server.baseUrl}/v1');

// Later, when the host decides to stop the runtime:
await engine.stopServer();
await engine.unloadModel();
await eventSubscription.cancel();
await logSubscription.cancel();
```

`importModelDirectory()` launches an Android Activity and must be called while
the plugin is attached to a Flutter Activity. If the host application already
downloaded a model into private storage, use:

```dart
final imported = await engine.importModelFromPath(modelDirectory.path);
```

## Choosing an inference backend

Query capabilities before offering an accelerator. `compiled` means that its
MNN host backend is included; `available` means that runtime initialization
passed on this device. Neither guarantees compatibility with every model.

```dart
final capabilities = await engine.getBackendCapabilities();
final opencl = capabilities.firstWhere((item) => item.backend == MnnBackend.opencl);
if (opencl.available) {
  await engine.loadModel(
    importedModel.modelId,
    options: const MnnLoadOptions(backend: MnnBackend.opencl),
  );
}
```

The API server must be stopped before loading or changing a backend. Omitted
options select CPU. Unavailable backends return `backend_unavailable`; the
plugin does not silently retry on CPU. The resident model's `backend` field
records the selection. MNN can still schedule unsupported operators on CPU.
A model's separate `mllm` encoder configuration is preserved; otherwise Omni
shares the main backend. Validate vision models on the chosen accelerator.

Default packages contain CPU/OpenCL/Vulkan only; Hexagon is Experimental.
Explicit experimental builds can bundle **v73, v75, v79 and v81** in one APK. The
plugin queries the device's cDSP architecture and extracts/loads only the exact
match; `MnnBackendCapability.dspArchitecture` reports the detected ISA. No manual
architecture selection is needed. A compatible Qualcomm DSP with FP16 HMX and
OEM FastRPC access is still required. Builds with `include_hexagon=false` disable
the host backend and omit the stub/DSP assets. See
[native build instructions](doc/BUILDING_NATIVE.md).
For Hexagon, the host app must set `packaging.jniLibs.useLegacyPackaging = true`
so the Android stub exists in `nativeLibraryDir`.

## Model directory

The plugin imports a complete MNN model directory rather than a single `.mnn`
file. A typical text model looks like this:

```text
Qwen3-0.6B-MNN/
├── config.json
├── llm.mnn
├── llm.mnn.weight
├── tokenizer.txt
├── llm_config.json
└── market_config.json          # Optional display metadata
```

The root `config.json` must contain a non-empty `llm_model`. Every referenced
model, weight, embedding, and tokenizer path must be relative to the model
directory and must exist. Imported models are copied to:

```text
<application filesDir>/mnn/models/<model-key>/
```

Model files and model licenses are not distributed with this plugin.

## OpenAI-compatible API

The default server address is `http://127.0.0.1:8081`.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/` | Built-in API test page |
| `GET` | `/health` | Engine, model, and server state |
| `GET` | `/v1/models` | The active model in OpenAI-compatible format |
| `POST` | `/v1/chat/completions` | Streaming or non-streaming chat completions |

Example request:

```shell
curl http://127.0.0.1:8081/v1/chat/completions \
  -H "Authorization: Bearer replace-with-a-runtime-secret" \
  -H "Content-Type: application/json" \
  --data-binary '{
    "model": "qwen3-0-6b-mnn",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": true,
    "max_tokens": 128
  }'
```

`max_tokens` is optional. When omitted or set to `-1`, generation continues
until the model emits an end token or the runtime reaches its context limit.
`max_completion_tokens` and `n_predict` are accepted as aliases.

Use `MnnServerBindMode.allInterfaces` to listen on `0.0.0.0`. This makes the
server reachable through Wi-Fi, hotspots, VPNs, and other IPv4 interfaces.
Always configure an API key unless the device is on a trusted network.

## Runtime rules

- A model must be loaded before the server starts.
- Stop the server before explicitly unloading, deleting, or replacing the
  active model.
- A second concurrent generation receives HTTP 429; requests are not queued.
- `stopServer()` closes generation admission before cancelling the active
  request. While the HTTP listener is shutting down, new requests receive
  HTTP 503 (`server_stopping`); requests still preparing input cannot start inference.
- `cancelGeneration()` cancels only the active generation; it does not stop the
  server. Standard causal models check prefill cancellation between chunks
  (128 tokens by default). Explicit `chunk`/`chunk_limits` are preserved;
  `chunk: 0` disables splitting, and legacy GLM/integer masks keep full prefill
  by default. A running chunk or image/audio encoder call must finish before
  cancellation takes effect.
- A generation failure releases the resident model and clears `activeModel` in
  runtime snapshots. Recover with `stopServer()`, `loadModel()`, then
  `startServer()`. Normal completion, length limits, and cancellation keep the
  model available for subsequent requests.
- Complete shutdown order is `stopServer()` followed by `unloadModel()`.

## Native binaries and reproducibility

The pub package contains:

```text
android/src/main/jniLibs/arm64-v8a/
├── libMNN.so
└── libmnn_engine_jni.so
```

Their exact source revision, toolchain, build flags, Build IDs, sizes, and
SHA-256 hashes are recorded in
[`native/android-arm64-v8a.json`](native/android-arm64-v8a.json). Maintainers
can reproduce them locally or with the repository's GitHub Actions workflow;
consumer builds never invoke that toolchain.

Run **Build Android native libraries** in Actions to download the `.so` files,
checksums and build logs. The default `include_hexagon=false` builds CPU/GPU only,
including on `native-v*` tags. Explicit opt-in builds all four DSP runtimes with
the pinned SDK Docker image; no SDK secret is required. A supplied SDK archive
is also supported. Example APK verification is optional. See the
[GitHub Actions instructions](doc/BUILDING_NATIVE.md#recommended-github-actions).

## Additional resources

- [Build the Android native libraries](doc/BUILDING_NATIVE.md)
- [中文 Native 编译指南](doc/BUILDING_NATIVE_ZH.md)
- [Example application](example/)

## License

`mnn_engine` is released under the [Apache License 2.0](LICENSE). Bundled
native code and third-party attribution are documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Model files are licensed
separately by their respective publishers.
