# mnn_engine

[English](README.md) | [简体中文](README_ZH.md)

[![pub package](https://img.shields.io/pub/v/mnn_engine.svg)](https://pub.dev/packages/mnn_engine)
[![构建 Android Native](https://github.com/ArkaneFans/mnn_engine/actions/workflows/build-native-android.yml/badge.svg)](https://github.com/ArkaneFans/mnn_engine/actions/workflows/build-native-android.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

在 Flutter Android 应用中运行 [Alibaba MNN](https://github.com/alibaba/MNN)
大语言模型，并将当前模型暴露为设备内 OpenAI 兼容 HTTP API。

`mnn_engine` 已随 pub.dev 包提供经过校验的 `arm64-v8a` Native 库。应用接入后
无需准备 MNN 源码、CMake、Android NDK、Linux 或 WSL，即可直接完成 Android 构建。

> [!IMPORTANT]
> 本项目是独立维护的社区插件，不是 Alibaba 官方 Flutter 插件。当前仍处于
> `0.x` 版本阶段，公共 API 后续可能继续演进。

## 功能特性

- 通过 Android Storage Access Framework 导入完整 MNN 模型目录。
- 支持从宿主应用私有存储直接导入已经下载完成的模型。
- 加载前校验 `config.json` 以及模型、权重、embedding、tokenizer 等引用文件。
- 支持模型导入、列表、加载、卸载和删除。
- 使用 Android 前台 Service 管理模型 Session 与 API Server 生命周期。
- 提供运行状态快照、状态事件流、日志快照和实时日志流。
- 可监听 loopback 或全部可用 IPv4 网络接口。
- 两种监听模式均支持可选 Bearer API Key。
- 提供 OpenAI 兼容的 `/v1/models` 和 `/v1/chat/completions`。
- 支持 SSE 流式响应、视觉输入、function tools、推理内容和取消当前生成。
- Native 库经过 AArch64、JNI exports、ELF 依赖和 Android 16 KB page 校验。

## 支持范围

| 项目 | 当前支持 |
| --- | --- |
| Flutter 平台 | 仅 Android |
| Android ABI | 仅 `arm64-v8a` |
| Android 最低版本 | API 28 |
| 插件 Compile SDK | 35 |
| Flutter | 3.35.0 或更高版本 |
| MNN | 3.6.0，commit `cc20f672af9e177e2fa338c332dc097de2fc9264` |
| 推理后端 | CPU |
| 活跃模型 | 同一时间一个 |
| 并发生成 | 同一时间一个 |

当前不支持 iOS、桌面平台、`armeabi-v7a`、`x86` 和 `x86_64`。

## 安装

在 Flutter 应用的 `pubspec.yaml` 中添加：

```yaml
dependencies:
  mnn_engine: ^0.0.1
```

然后执行：

```shell
flutter pub get
```

宿主 Android 应用需要使用 API 28，并将发布 ABI 限制为 ARM64。Kotlin DSL
（`android/app/build.gradle.kts`）配置如下：

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

Groovy（`android/app/build.gradle`）配置如下：

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

插件 Manifest 会合并 Internet、前台 Service、data sync 前台 Service 和通知权限。
Android 13 及以上的通知运行时授权，应由宿主应用按照自己的交互流程申请。

## 快速开始

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
  throw StateError('MNN Native Runtime 不可用。');
}

// 打开 Android 系统目录选择器，并把完整模型目录导入应用私有存储。
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
    throw StateError('端口 8081 当前不可用。');
  }
  rethrow;
}
print('OpenAI 兼容地址：${server.baseUrl}/v1');

// 宿主决定完整停止服务时：
await engine.stopServer();
await engine.unloadModel();
await eventSubscription.cancel();
await logSubscription.cancel();
```

`importModelDirectory()` 会启动 Android Activity，因此必须在插件已附加 Flutter
Activity 时调用。如果宿主应用已经把模型下载到私有目录，可以直接使用：

```dart
final imported = await engine.importModelFromPath(modelDirectory.path);
```

## 模型目录

插件导入的是完整 MNN 模型目录，而不是单个 `.mnn` 文件。典型文本模型结构如下：

```text
Qwen3-0.6B-MNN/
├── config.json
├── llm.mnn
├── llm.mnn.weight
├── tokenizer.txt
├── llm_config.json
└── market_config.json          # 可选显示元数据
```

根目录中的 `config.json` 必须包含非空 `llm_model`。模型、权重、embedding 和
tokenizer 等所有引用路径必须是目录内相对路径，并且对应文件必须存在。模型导入位置为：

```text
<application filesDir>/mnn/models/<model-key>/
```

本插件不分发模型文件，模型许可证由对应模型发布方单独提供。

## OpenAI 兼容 API

默认 Server 地址为 `http://127.0.0.1:8081`。

| Method | Path | 说明 |
| --- | --- | --- |
| `GET` | `/` | 内置 API 测试页 |
| `GET` | `/health` | Engine、模型和 Server 状态 |
| `GET` | `/v1/models` | 当前模型的 OpenAI 兼容信息 |
| `POST` | `/v1/chat/completions` | 流式或非流式 Chat Completions |

请求示例：

```shell
curl http://127.0.0.1:8081/v1/chat/completions \
  -H "Authorization: Bearer replace-with-a-runtime-secret" \
  -H "Content-Type: application/json" \
  --data-binary '{
    "model": "qwen3-0-6b-mnn",
    "messages": [{"role": "user", "content": "你好"}],
    "stream": true,
    "max_tokens": 128
  }'
```

使用 `MnnServerBindMode.allInterfaces` 时 Server 会监听 `0.0.0.0`，能够通过
Wi-Fi、热点、VPN 等 IPv4 接口访问。除非设备处于可信网络，否则应始终配置 API Key。

## 运行约束

- 启动 Server 前必须先加载模型。
- Server 运行时不能卸载、删除或替换活跃模型。
- 第二个并发生成请求会收到 HTTP 429。
- `cancelGeneration()` 只取消当前生成，不会停止 Server。
- 完整停止顺序为 `stopServer()`，然后调用 `unloadModel()`。

## Native 产物与可复现构建

pub.dev 包内直接包含：

```text
android/src/main/jniLibs/arm64-v8a/
├── libMNN.so
└── libmnn_engine_jni.so
```

其源码版本、工具链、构建参数、Build ID、文件大小和 SHA-256 均记录在
[`native/android-arm64-v8a.json`](native/android-arm64-v8a.json) 中。维护者可以
在本地或 GitHub Actions 中重新生成这些文件，普通消费者的 Gradle 构建不会触发
Native 编译。

## 其他资源

- [Native 编译指南](doc/BUILDING_NATIVE_ZH.md)
- [Native build guide](doc/BUILDING_NATIVE.md)
- [示例应用](example/)

## License

`mnn_engine` 使用 [Apache License 2.0](LICENSE)。Native 依赖和第三方版权信息见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。模型文件使用各自发布方的独立许可证。
