## 0.1.0

- Bundle verified Android arm64 native libraries for pub.dev consumers.
- Stop compiling MNN from consumer Gradle builds.
- Use the Ktor CIO server engine to avoid Netty packaging conflicts in host applications.
- Add reproducible GitHub Actions native builds and public artifact metadata.
- Add bilingual package documentation and pub.dev publishing guidance.
- Make server binding authoritative, reuse safe local ports, and report real
  bind conflicts without a race-prone preflight in the main startup path.

## 0.0.1

- Add Android arm64 MNN 3.6.0 native build and JNI runtime.
- Add complete model directory import and validation.
- Add foreground Ktor OpenAI-compatible server and SSE chat completions.
- Add runtime state and independent log event streams.
