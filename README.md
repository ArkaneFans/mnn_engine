# mnn_engine

> [!WARNING]
> **本项目正在开发中。** 当前版本用于验证 Flutter Android 应用中的 MNN 本地文本模型推理与 API Server 能力，公开 API、构建流程和模型兼容范围仍可能变化。现阶段不建议用于生产环境。

`mnn_engine` 是一个 Android-only Flutter 插件，用于集成 [Alibaba MNN](https://github.com/alibaba/MNN) 本地推理引擎，并在 Android 设备上提供 OpenAI 兼容的 loopback HTTP API Server。

本仓库不是 Alibaba 官方 Flutter 插件。MNN 源码通过 Git 子模块引用，并保持在经过验证的固定提交上。

## 项目目标

- 将 MNN 文本大模型推理能力封装为可独立维护的 Flutter 插件。
- 隔离 Dart、Android Service、JNI 和 MNN Native Runtime 的职责。
- 允许宿主应用管理模型导入、加载、卸载、Server 生命周期和运行日志。
- 在设备本地提供 OpenAI 兼容的模型查询与 Chat Completions API。
- 通过固定 MNN commit、构建参数和工具链版本提供可复现的 Android Native 构建流程。
- 让宿主应用通过本地路径引用插件，便于单独升级和验证 MNN。

## 核心功能

- 通过 Android Storage Access Framework（SAF）选择并导入完整 MNN 模型目录。
- 校验模型 `config.json` 及其引用的模型、权重、embedding 和 tokenizer 文件。
- 加载、卸载和删除导入的本地文本模型。
- 使用 Android 前台 Service 管理模型 Session 和 API Server 生命周期。
- 提供运行状态快照、状态事件流、日志快照和实时日志流。
- 启动仅监听设备 loopback 的 Ktor Netty Server。
- 支持可选 Bearer API Key。
- 支持 OpenAI 兼容的流式和非流式 Chat Completions。
- 支持取消当前生成请求。
- 校验 Native ELF ABI、16 KB page alignment、JNI exports、依赖关系和 APK 打包结果。

## 当前支持范围

| 项目 | 当前状态 |
| --- | --- |
| Flutter 平台 | Android only |
| Android ABI | `arm64-v8a` only |
| Android 最低版本 | API 28 |
| Android Compile SDK | 35 |
| MNN | 3.6.0，commit `cc20f672af9e177e2fa338c332dc097de2fc9264` |
| Native 构建 | Android NDK r27 系列、CMake 3.22.1、Ninja |
| 推理后端 | CPU |
| 模型类型 | 文本 LLM |
| 活跃模型 | 同一时间一个 |
| 生成请求 | 同一时间一个 |
| Server 地址 | `127.0.0.1` 或 `localhost` |
| OpenAI API | `/v1/models`、`/v1/chat/completions` |

当前不支持：

- iOS、Windows、macOS、Linux Flutter 平台；
- `armeabi-v7a`、`x86`、`x86_64` Android ABI；
- `/v1/completions`；
- LAN 监听和远程设备直连；
- 多模型同时加载或并发生成队列；
- multimodal、vision、audio、embedding、tool calling；
- OpenCL、QNN 等非 CPU 后端；
- 从 pub.dev 安装。

## 整体架构

```mermaid
flowchart LR
    Client["设备内客户端<br/>或 ADB 端口转发"] -->|"HTTP / SSE"| Server["Ktor Netty<br/>OpenAI-compatible Server"]

    subgraph Host["Flutter Android 宿主应用进程"]
        App["Flutter UI / 业务层"] --> Dart["MnnEngine Dart API"]
        Dart <-->|"MethodChannel"| Plugin["MnnEnginePlugin"]
        Plugin --> Service["MnnEngineService<br/>Foreground Service"]
        Service --> Models["Model Importer<br/>Repository / Validator"]
        Service --> Runtime["MnnRuntimeManager"]
        Service --> Server
        Service --> Logs["Runtime State / Log Store"]
        Logs -->|"EventChannel"| Dart
        Runtime --> JNI["JNI Session Adapter"]
        JNI --> Native["MNN Native LLM Runtime"]
    end

    SAF["Android SAF<br/>模型目录"] --> Models
    Submodule["MNN Git Submodule"] -->|"WSL2 Native Build"| Native
```

前台 Service 让模型和 Server 生命周期不依赖某个 Flutter 页面，但当前没有设置 `android:process`，Service 与宿主应用运行在同一 Android 进程中。

## 仓库结构

```text
mnn_engine/
├── MNN/                         # Alibaba MNN Git submodule
├── android/
│   └── src/main/
│       ├── cpp/                 # JNI bridge 和 MNN LLM adapter
│       ├── kotlin/              # Service、模型管理、Server、日志
│       └── assets/              # 内置 API 测试页
├── lib/                         # Flutter/Dart 公共 API
├── scripts/                     # 环境准备、Native 构建和产物校验
├── example/                     # 最小 Flutter 插件示例
├── test/                        # Dart 单元测试
└── .native/                     # 本机生成的 Native 产物，不进入 Git
```

## 获取源码

首次克隆必须初始化 MNN 子模块：

```powershell
git clone --recurse-submodules https://github.com/ArkaneFans/mnn_engine.git
cd mnn_engine
git submodule update --init --recursive
git -C MNN rev-parse HEAD
```

最后一条命令应输出：

```text
cc20f672af9e177e2fa338c332dc097de2fc9264
```

普通 `git clone` 后如果遗漏了 `--recurse-submodules`，执行 `git submodule update --init --recursive` 即可补齐。

## 构建环境

当前经过验证的开发环境为：

- Windows 上使用 PowerShell 7；
- WSL2 发行版 `Ubuntu-22.04`；
- Flutter 和 Android SDK 安装在 Windows；
- MNN 与 JNI Native 代码在 WSL2 中编译；
- WSL2 中已安装 Android NDK、Ninja、Git、Python 3 和常用 GNU 工具；
- Android NDK 由 WSL 环境变量 `ANDROID_NDK` 指定，未设置时默认使用 `~/android-ndk-r27d`；
- CMake 固定为 3.22.1，并安装到 WSL 用户目录，不替换系统 CMake。

构建脚本要求 `/usr/bin/ninja` 存在。可以先在 WSL 中检查环境：

```bash
test -f "${ANDROID_NDK:-$HOME/android-ndk-r27d}/build/cmake/android.toolchain.cmake"
/usr/bin/ninja --version
git --version
python3 --version
```

## Native 编译流程

所有以下命令均从插件根目录执行。

### 1. 初始化依赖

```powershell
git submodule update --init --recursive
flutter pub get
```

### 2. 准备隔离的 CMake 3.22.1

```powershell
pwsh -File .\scripts\prepare_mnn_build_env.ps1
```

脚本会下载经过 SHA-256 校验的 CMake 归档，并安装到：

```text
~/.local/share/mnn_engine/toolchains/cmake-3.22.1/
```

该操作不会修改 WSL 的系统 CMake。如果 WSL 发行版名称不同，可以显式传入：

```powershell
pwsh -File .\scripts\prepare_mnn_build_env.ps1 -Distro Ubuntu-22.04
```

### 3. 编译 MNN 和 JNI Bridge

```powershell
pwsh -File .\scripts\build_mnn_android.ps1
```

构建分为两步：

1. 使用固定 MNN commit 编译 `libMNN.so`；
2. 编译并链接 `libmnn_engine_jni.so`。

构建缓存位于 WSL 用户目录下的 `~/.cache/mnn_engine/`。最终供 Gradle 打包的文件写入：

```text
.native/generated/arm64-v8a/
├── libMNN.so
├── libmnn_engine_jni.so
└── mnn_build_info.json
```

`.native/` 已被 Git 忽略。构建指纹包含 MNN commit、NDK、CMake、Ninja、关键 CMake flags 和 JNI 源码哈希；这些内容发生变化时会生成对应的新缓存。

### 4. 校验 Native 产物

```powershell
pwsh -File .\scripts\verify_mnn_artifacts.ps1
```

校验内容包括：

- AArch64 ELF 类型；
- 16 KB `LOAD` segment alignment；
- JNI 导出符号；
- `libmnn_engine_jni.so` 对 `libMNN.so` 的依赖；
- MNN commit、工具链和构建参数元数据。

Gradle 的 `preBuild` 会检查这些产物。在 Windows 上发现产物缺失时，会通过 PowerShell 和 WSL2 触发 Native 构建；显式执行上述步骤更便于定位环境或编译错误。

### 5. Flutter 检查与示例运行

```powershell
flutter analyze
flutter test

cd example
flutter pub get
flutter run
```

`example/` 当前是用于确认插件注册和 Native 初始化的最小示例，不是完整的模型管理应用。

构建宿主 APK 后，可以继续校验 APK 内实际打包的 Native 文件：

```powershell
pwsh -File .\scripts\verify_mnn_artifacts.ps1 `
  -ApkPath <path-to-app.apk>
```

## 在 Flutter 应用中接入

当前推荐将本仓库克隆到宿主项目附近，并使用本地路径依赖。Dart/Flutter 的 Git dependency 不保证初始化插件内部的 MNN 子模块，因此暂不作为受支持的集成方式。

在宿主应用的 `pubspec.yaml` 中添加：

```yaml
dependencies:
  mnn_engine:
    path: ../mnn_engine
```

然后执行：

```powershell
flutter pub get
```

宿主 Android 应用的 `minSdk` 必须不低于 28。插件 Manifest 已声明 Internet、前台 Service、data sync 前台 Service 和通知权限；Android 13 及以上的通知运行时授权应由宿主应用根据自身交互流程处理。

## Dart 使用流程

推荐生命周期为：

```text
initialize
  -> import/list model
  -> load model
  -> check port
  -> start server
  -> serve requests
  -> stop server
  -> unload model
```

示例：

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

final info = await engine.initialize();
print('MNN ${info.mnnVersion} (${info.mnnCommit})');

// 打开 Android 系统目录选择器，并把选中的完整目录复制到应用私有目录。
final imported = await engine.importModelDirectory();
await engine.loadModel(imported.modelId);

final port = await engine.checkPort(port: 8081);
if (!port.available && !port.ownedByMnn) {
  throw StateError(port.message ?? 'Port 8081 is unavailable.');
}

final server = await engine.startServer(
  port: 8081,
  apiKey: 'replace-with-a-runtime-secret',
);
print(server.baseUrl);

// 页面或业务模块销毁时取消 Dart 监听；是否停止 Server 由宿主生命周期决定。
await eventSubscription.cancel();
await logSubscription.cancel();
```

`importModelDirectory()` 会启动 Android Activity，因此需要在已附加 Activity 的 Flutter UI 环境中调用。模型加载、Server 启停等耗时操作由插件转移到后台线程。

重要状态约束：

- 启动 Server 前必须先加载模型；
- Server 运行时不能卸载当前模型；
- 活跃模型不能被删除或覆盖；
- 同一时间只允许一个生成请求，第二个请求返回 HTTP 429；
- `cancelGeneration()` 只取消当前 MNN 生成，不会停止 Server；
- 完整清理顺序是 `stopServer()` 后调用 `unloadModel()`。

## 模型目录

插件导入的是完整目录，而不是单个 `.mnn` 文件。一个典型文本模型目录如下：

```text
Qwen3-0.6B-MNN/
├── config.json
├── llm.mnn
├── llm.mnn.weight
├── tokenizer.txt
├── llm_config.json
└── market_config.json          # 可选，作为显示名称等元数据来源
```

最低要求：

- 根目录存在可读取的 `config.json`；
- `config.json` 包含非空的 `llm_model`；
- `llm_model` 以及配置中出现的 `llm_weight`、`embedding_model`、`embedding_file`、`tokenizer_file` 等路径必须是目录内的相对路径；
- 所有被引用的文件必须存在。

插件会把整个目录复制到宿主应用私有目录：

```text
<application filesDir>/mnn_test/models/<model-key>/
```

测试数据、staging、runtime 和 diagnostics 均位于统一的 `<application filesDir>/mnn_test/` 根目录下。第一阶段会把非 CPU `backend_type` 覆盖为 CPU，并对 visual/audio 配置给出不受支持的警告。

仓库提供固定版本的 Qwen3-0.6B-MNN 测试模型下载脚本：

```powershell
pwsh -File .\scripts\download_qwen3_0_6b_test_model.ps1
```

默认下载到 `.test-models/Qwen3-0.6B-MNN/`。该目录已被 Git 忽略，模型文件及其许可证不随本插件仓库发布。

## 本地 HTTP API

Server 默认地址：

```text
http://127.0.0.1:8081
```

可用端点：

| Method | Path | 说明 |
| --- | --- | --- |
| `GET` | `/` | 内置 API 测试页 |
| `GET` | `/health` | Engine、模型和 Server 状态 |
| `GET` | `/v1/models` | 当前加载模型的 OpenAI 兼容列表 |
| `POST` | `/v1/chat/completions` | 流式或非流式文本生成 |

设置 `apiKey` 后，除静态测试页 `/` 外的 API 端点要求：

```http
Authorization: Bearer <api-key>
```

Chat Completions 当前接受：

- `model`：可选；提供时必须匹配当前加载模型 ID；
- `messages`：必填，支持 `system`、`user`、`assistant`，`content` 必须是字符串；
- `stream`：可选，默认 `false`；
- `temperature`：`0.0` 到 `2.0`；
- `top_p`：`0.0` 到 `1.0`；
- `max_tokens`：`1` 到 `8192`，默认 `512`；
- `n`：只能为 `1`；
- `frequency_penalty` 和 `presence_penalty`：只能为 `0`。

请求体最大为 2 MiB。`tools`、`tool_choice`、`parallel_tool_calls`、`response_format`、`logprobs` 和 `top_logprobs` 当前不受支持。

### 从开发机访问真机 Server

Server 只监听 Android 设备 loopback。开发机调试时使用 ADB 端口转发：

```powershell
adb forward tcp:18081 tcp:8081
```

查询模型：

```powershell
curl.exe http://127.0.0.1:18081/v1/models `
  -H "Authorization: Bearer replace-with-a-runtime-secret"
```

非流式请求：

```powershell
curl.exe http://127.0.0.1:18081/v1/chat/completions `
  -H "Authorization: Bearer replace-with-a-runtime-secret" `
  -H "Content-Type: application/json" `
  --data-binary '{"model":"local/Qwen3-0.6B-MNN","messages":[{"role":"user","content":"Hello"}],"max_tokens":128}'
```

流式请求：

```powershell
curl.exe -N http://127.0.0.1:18081/v1/chat/completions `
  -H "Authorization: Bearer replace-with-a-runtime-secret" `
  -H "Content-Type: application/json" `
  --data-binary '{"model":"local/Qwen3-0.6B-MNN","messages":[{"role":"user","content":"Hello"}],"stream":true,"max_tokens":128}'
```

实际模型 ID 以 `GET /v1/models` 返回值为准。设备内客户端可以直接使用 `http://127.0.0.1:8081/v1` 作为 OpenAI-compatible base URL。

## MNN 子模块升级

升级 MNN 时应使用明确的 tag 或 commit，并将子模块变更作为独立提交：

```powershell
git -C MNN fetch origin --tags
git -C MNN checkout <mnn-tag-or-commit>

pwsh -File .\scripts\build_mnn_android.ps1
pwsh -File .\scripts\verify_mnn_artifacts.ps1
flutter analyze
flutter test

git add MNN
git commit -m "chore: update MNN submodule to <version>"
```

升级后还应重新构建宿主 APK并执行真机模型加载、流式/非流式生成、取消生成、Server 重启和应用生命周期验证。不要直接修改 `MNN/` 内的官方源码来绕过构建问题；确需补丁时应使用可审查、可重放的独立 patch 或 fork。

## License

本插件代码使用 [Apache License 2.0](LICENSE)。

`MNN/` 是独立 Git 子模块，其源码、版权和第三方依赖遵循 MNN 仓库中的许可证与声明。测试模型不属于本仓库，使用前请检查对应模型仓库的许可证。
