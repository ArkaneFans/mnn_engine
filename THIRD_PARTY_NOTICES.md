# Third-party notices

The Android native libraries distributed with `mnn_engine` are built from the
projects and toolchains listed below. The public build manifest in
[`native/android-arm64-v8a.json`](native/android-arm64-v8a.json) records the
exact MNN commit, toolchain versions, build flags, Build IDs, sizes, and
SHA-256 digests for the bundled artifacts.

## Alibaba MNN

- Project: [Alibaba MNN](https://github.com/alibaba/MNN)
- Version: 3.6.0
- Commit: `cc20f672af9e177e2fa338c332dc097de2fc9264`
- Copyright: Copyright 2018 Alibaba Group
- License: Apache License 2.0

The Apache License 2.0 text is included in this package as [`LICENSE`](LICENSE).
`mnn_engine` is an independent community plugin and is not an official Alibaba
Flutter package.

## FlatBuffers

- Project: [Google FlatBuffers](https://github.com/google/flatbuffers)
- License: Apache License 2.0

MNN uses FlatBuffers data structures and generated code. The Apache License
2.0 text is included as [`LICENSE`](LICENSE).

## half

- Project component: MNN `3rd_party/half`
- Copyright: Copyright 2012-2017 Christian Rau
- License: MIT License

The full license text is included as
[`third_party_licenses/HALF.txt`](third_party_licenses/HALF.txt).

## LLVM libc++ / Android NDK

The native libraries are built with Android NDK r27d and
`ANDROID_STL=c++_static`. LLVM libc++ and libc++abi are licensed under the
Apache License 2.0 with the LLVM exception. The exception is included as
[`third_party_licenses/LLVM-exception.txt`](third_party_licenses/LLVM-exception.txt).

Downstream application distributors remain responsible for reviewing the
licenses of their models and any additional native or Dart dependencies they
combine with this package.
