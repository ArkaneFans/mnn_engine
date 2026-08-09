# mnn_engine example

This minimal Android application verifies plugin registration, loads the
bundled ARM64 native libraries, calls `MnnEngine.initialize()`, and displays
the MNN version, commit, ABI, and runtime status returned by the plugin.

Run it on an ARM64 Android device with API 28 or newer:

```shell
flutter pub get
flutter run
```

The example intentionally does not bundle a model. See the root
[`README.md`](../README.md) for model import, lifecycle, and OpenAI-compatible
server examples.
