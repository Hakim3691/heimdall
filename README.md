# Heimdall

Ambient, 100% on-device screen co-pilot for Android 12+ (API 31). Heimdall watches for the
moment a user is stuck — a shaky, hesitating or unchanging screen — and surfaces a short
non-touchable tooltip over the app. Inference runs locally on llama.cpp; the app has no
network permission at all.

## What it does

| Requirement | Implementation |
|---|---|
| FR-01 overlay | `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCHABLE` translucent Material 3 pill, auto-dismiss after 4.5 s |
| FR-02 trigger cascade | gyro tremor variance (0.02–1.20), hesitation ≥ 1.5 s, dHash Hamming distance ≤ 2 |
| FR-03 local VLM | llama.cpp (pinned commit) + `mtmd` multimodal front-end, MiniCPM-V 2.6 Q4_K_M + `mmproj-f16.gguf` |
| FR-04 frames | `MediaProjection` foreground service, downsampled surface |
| FR-05 gating | JSON-schema gate drops `intervene == false` and low-confidence answers |
| FR-06 thermals | `PowerManager` thermal listeners scale latency / pause capture |
| FR-07 onboarding | model storage checks and mmap load from `/sdcard/Heimdall/models/` |
| NFR-1 | battery/thermal budget is a benchmark gate on a named reference device |

## Privacy

* `android.permission.INTERNET` is **not** declared — the APK cannot open a socket.
* No analytics, no crash reporting, no telemetry SDKs.
* Frames are processed in memory and never written to disk.
* The CI privacy gate fails the build if a network permission or network API reappears.

## Modules

```
app                  wiring, services, onboarding activity
core/model           pure-data contracts (intervention, config, thermal tiers)
core/common          clock/log abstractions
core/testing         FakeClock, SyntheticFrames, RecordingLog
engine/trigger       gyro tremor, hesitation, dHash cascade
engine/gate          pure-JVM MiniJson, prompt builder, intervention gate
engine/vlm           NativeVlmEngine (JNI) + FakeVlmEngine
engine/governor      thermal → latency policy
ui/overlay           window controller + tooltip view
acceptance           Gherkin scenarios on the JUnit 5 platform
benchmark            macrobenchmark module
```

Everything Android-touching sits behind an interface (`SensorSource`, `FrameSource`,
`ThermalSource`, `VlmEngine`, `Clock`) so the behaviour is testable on a plain JVM.

## Build

```bash
# JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1
scripts/fetch-llama.sh                 # checks out the pinned llama.cpp commit
./gradlew :app:assembleDebug           # or assembleRelease
```

Native library notes: llama.cpp is built for `arm64-v8a` (add `x86_64` for emulators with
`-Pheimdall.abis=arm64-v8a,x86_64`). Vulkan is opt-in with `-Pheimdall.vulkan=ON`; the default
offline build uses the CPU backend. Exceptions and RTTI must stay enabled for llama.cpp.

Release signing falls back to the debug key when `keystore.properties` is absent, so
`assembleRelease` always yields an installable APK. Supply `keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or the CI secrets for a stable
release identity.

Models are never committed; fetch them separately and place them in
`/sdcard/Heimdall/models/`:

* `minicpm-v-2_6-Q4_K_M.gguf`
* `mmproj-model-f16.gguf`

## Tests

```bash
./gradlew test                  # unit + Robolectric
./gradlew :acceptance:test      # Gherkin / Cucumber BDD suite
```

`RequirementTraceabilityTest` fails the build if a P0 requirement tag has no scenario, so the
feature files are the contract between requirements and code.

Device-level instrumented tests run in CI on API 31 and API 34 emulators.

## CI

| Workflow | Purpose |
|---|---|
| `ci.yml` | spotless, detekt, lint, unit tests, acceptance tests |
| `native.yml` | fetches pinned llama.cpp and assembles the APK |
| `privacy-gate.yml` | blocks network permissions/APIs and verifies the APK manifest |
| `instrumented.yml` | emulator matrix (API 31, 34) |
| `release.yml` | tags build and publish the APK; signs with secrets when available |

## Honest limitations

* Battery (< 3.5 %/h) and thermal (ΔT < 3 °C) targets are benchmark gates on a reference
  device; they are not asserted by CI.
* The APK ships the CPU backend by default; Vulkan support is a build-time opt-in that still
  needs on-device verification.
