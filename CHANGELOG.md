## 0.0.2

- Use the imported model directory name as the MNN runtime and API model ID.
- Add model-directory renaming with case-insensitive duplicate-name checks.
- Add import-result APIs with optional automatic conflict-free model naming.

## 0.0.1

- Bundle verified Android arm64 native libraries for pub.dev consumers.
- Stop compiling MNN from consumer Gradle builds.
- Use the Ktor CIO server engine to avoid Netty packaging conflicts in host applications.
- Add reproducible GitHub Actions native builds and public artifact metadata.
- Add bilingual package documentation and pub.dev publishing guidance.
- Make server binding authoritative, reuse safe local ports, and report real
  bind conflicts without a race-prone preflight in the main startup path.
- Add Android arm64 MNN 3.6.0 native build and JNI runtime.
- Add complete model directory import and validation.
- Add foreground Ktor OpenAI-compatible server and SSE chat completions.
- Add runtime state and independent log event streams.
