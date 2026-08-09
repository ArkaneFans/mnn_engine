# Android Native 编译指南

本文档面向需要重新编译 `mnn_engine` Native 库的贡献者。普通用户从
pub.dev 安装插件后会直接使用已经打包好的库，不需要安装这套工具链。

## 编译要求

- 支持子模块的 Git
- Flutter 和 Android SDK
- Linux，或 Windows + WSL2（推荐 Ubuntu 22.04）
- Android NDK `27.3.13750724`（r27d）
- CMake `3.22.1`、Ninja、Python 3 和 C/C++ 编译工具
- ARM64 Android 目标（`arm64-v8a`）

插件基于 MNN 3.6.0，固定 commit 为
`cc20f672af9e177e2fa338c332dc097de2fc9264`。

## 推荐：使用 GitHub Actions

如果不想在本机配置编译环境，请先 Fork 仓库，然后进入自己 Fork 后的 **Actions**
页面，按 GitHub 提示启用工作流并运行 **Build Android native libraries**。

工作流会自动编译、校验 Native 库，并可选构建 example APK；完成后下载 artifact，
解压到插件根目录即可。

## Windows + WSL2 本地编译

在插件根目录使用 PowerShell 7 依次执行：

```powershell
git submodule update --init --recursive
pwsh -File .\scripts\prepare_mnn_build_env.ps1
pwsh -File .\scripts\build_mnn_android.ps1
pwsh -File .\scripts\verify_mnn_artifacts.ps1
pwsh -File .\scripts\package_mnn_artifacts.ps1
```

如果 WSL 发行版名称不是 `Ubuntu-22.04`，给每个 PowerShell 脚本加上
`-Distro <名称>`。

编译中间产物位于 `.native/generated/`。打包完成后，正式库会写入：

```text
android/src/main/jniLibs/arm64-v8a/
├── libMNN.so
└── libmnn_engine_jni.so
```

同时会更新 `native/android-arm64-v8a.json`，其中记录版本、哈希和构建信息。

## Linux 本地编译

安装 Android SDK API 35、NDK `27.3.13750724` 和 CMake `3.22.1`，然后设置路径：

```bash
export ANDROID_NDK="$ANDROID_SDK_ROOT/ndk/27.3.13750724"
export MNN_CMAKE="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/cmake"
```

在插件根目录执行：

```bash
git submodule update --init --recursive
bash scripts/build_mnn_android.sh "$PWD"
bash scripts/verify_mnn_artifacts.sh "$PWD"
bash scripts/package_mnn_artifacts.sh "$PWD"
```

## 编译后校验

先运行 Dart 检查，再构建 ARM64 example APK：

```powershell
flutter analyze
flutter test

Push-Location example
flutter pub get
flutter build apk --release --target-platform android-arm64
Pop-Location

pwsh -File .\scripts\verify_mnn_artifacts.ps1 `
  -ApkPath <app-release.apk 的绝对路径>
```

至少应在一台 ARM64 真机上确认插件能够初始化并加载模型。

## 什么时候需要重新编译

修改 MNN/JNI Native 源码、MNN commit、NDK/CMake 版本或 Native 编译参数后需要重新
编译。只修改 Dart 代码或文档时，可以继续使用现有 Native 库。

## 常见问题

- **找不到 WSL 发行版：** 安装 Ubuntu 22.04，或使用 `-Distro` 指定名称。
- **找不到 NDK/CMake：** 检查 `ANDROID_NDK` 和 `MNN_CMAKE` 是否指向固定版本。
- **ABI 不受支持：** 宿主应用只配置 `arm64-v8a`。
- **MNN 子模块为空：** 执行 `git submodule update --init --recursive`。
