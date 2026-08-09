# Build the Android native libraries

This guide is for contributors who need to rebuild the native libraries in
`mnn_engine`. Applications installed from pub.dev already receive verified
libraries and do not need this toolchain.

## Requirements

- Git with submodule support
- Flutter and the Android SDK
- Linux, or Windows with WSL2 (Ubuntu 22.04 is recommended)
- Android NDK `27.3.13750724` (r27d)
- CMake `3.22.1`, Ninja, Python 3, and a C/C++ build toolchain
- An ARM64 Android target (`arm64-v8a`)

The plugin is built against MNN 3.6.0 at commit
`cc20f672af9e177e2fa338c332dc097de2fc9264`.

## Recommended: GitHub Actions

For a clean, reproducible build, first fork the repository. Open the **Actions**
tab in your fork, enable workflows when GitHub asks for confirmation, and run
**Build Android native libraries**.

After the job finishes, download the generated artifact and copy its contents
to the plugin root. The workflow also verifies the libraries and can build the
example APK.

## Local build on Windows + WSL2

Run these commands from the plugin root in PowerShell 7:

```powershell
git submodule update --init --recursive
pwsh -File .\scripts\prepare_mnn_build_env.ps1
pwsh -File .\scripts\build_mnn_android.ps1
pwsh -File .\scripts\verify_mnn_artifacts.ps1
pwsh -File .\scripts\package_mnn_artifacts.ps1
```

If your WSL distribution is not named `Ubuntu-22.04`, pass its name to each
PowerShell script with `-Distro <name>`.

The build first creates temporary files under `.native/generated/`. Packaging
then validates and copies the release libraries to:

```text
android/src/main/jniLibs/arm64-v8a/
├── libMNN.so
└── libmnn_engine_jni.so
```

It also updates `native/android-arm64-v8a.json`, which records the native
version, hashes, and build metadata.

## Local build on Linux

Install the Android SDK packages for API 35, NDK `27.3.13750724`, and CMake
`3.22.1`, then set the toolchain paths:

```bash
export ANDROID_NDK="$ANDROID_SDK_ROOT/ndk/27.3.13750724"
export MNN_CMAKE="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/cmake"
```

From the plugin root:

```bash
git submodule update --init --recursive
bash scripts/build_mnn_android.sh "$PWD"
bash scripts/verify_mnn_artifacts.sh "$PWD"
bash scripts/package_mnn_artifacts.sh "$PWD"
```

## Verify the result

Run the Dart checks and build the ARM64 example APK:

```powershell
flutter analyze
flutter test

Push-Location example
flutter pub get
flutter build apk --release --target-platform android-arm64
Pop-Location

pwsh -File .\scripts\verify_mnn_artifacts.ps1 `
  -ApkPath <absolute-path-to-app-release.apk>
```

At minimum, confirm that the example can initialize the plugin and load a
model on an ARM64 device.

## When to rebuild

Rebuild when changing MNN or JNI native code, the MNN commit, the NDK/CMake
version, or native build flags. Dart-only or documentation changes can reuse
the existing libraries.

## Common issues

- **Missing WSL distribution:** install Ubuntu 22.04 or pass `-Distro`.
- **Missing NDK/CMake:** set `ANDROID_NDK` and `MNN_CMAKE` to the pinned
  versions above.
- **Unsupported ABI:** configure the host app for `arm64-v8a` only.
- **Missing submodule:** run `git submodule update --init --recursive`.
