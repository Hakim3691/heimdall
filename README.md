# Heimdall

Ambient, 100% on-device screen co-pilot for Android 12+ (API 31). Heimdall watches for the
moment a user is stuck — a shaky, hesitating or unchanging screen — and surfaces a short
non-touchable tooltip over the app. Inference runs locally on llama.cpp; the app has no
network permission at all.

## What it does

| Requirement | Implementation |
|---|---|
| FR-01 overlay | `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCHABLE` translucent Material 3 pill, auto-dismiss after 4.5 s |
| FR-02 trigger cascade | gyro tremor variance (0.02–1.20) or a ≥ 0.08 rad/s correction peak, hesitation ≥ 1.5 s, dHash Hamming distance ≤ 2 |
| FR-03 local VLM | llama.cpp (pinned commit) + `mtmd` multimodal front-end, MiniCPM-V 2.6 Q4_K_M + `mmproj-f16.gguf` |
| FR-04 frames | `MediaProjection` foreground service, downsampled surface |
| FR-05 gating | JSON-schema gate drops `intervene == false` and low-confidence answers |
| FR-06 thermals | `PowerManager` thermal listeners scale latency / pause capture |
| FR-07 onboarding | system file picker for the two GGUF files, validated and mmapped in place (zero copy) |
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

Models are never committed or bundled. Heimdall reads the two MiniCPM-V 2.6 GGUF files **in place**,
straight from wherever you downloaded them:

* `ggml-model-Q4_K_M.gguf` (~5.5 GB, language weights)
* `mmproj-model-f16.gguf` (~1 GB, vision projector)

Download them from `openbmb/MiniCPM-V-2_6` on Hugging Face in the phone browser (the app has no
network permission, so **Get recommended model** in the app hands off to the browser) and pick each
one with **Choose file**. The picker grants read-only access and the file is never copied, moved or
uploaded: the language weights are read from the picked document's own descriptor, which the native
loader duplicates and hands to llama.cpp as a `FILE*`, so nothing ever reopens `/proc/self/fd/<n>`
(the reopen Android content providers refuse with `Permission denied`). The weights file can stay in
Downloads or be deleted at any time.

The **vision projector** is the exception: `mtmd` only takes a file path, so it is imported once
into the app's own storage (`filesDir/models/mmproj-<hash>.gguf`, ~1 GB) the first time it is picked.
The copy is reused for later sessions, replaced when you pick a different projector, and removed
when you clear the app's storage; it is never re-imported per session. Cloud-only documents are
rejected because they must be readable as a descriptor.

Compatibility is a property of the **pair**, and the bundled llama.cpp + mtmd front-end supports
more than one vision family. The GGUF metadata prefix is read at pick time:

* `qwen2`/`minicpmv` weights need a `resampler` projector — the official MiniCPM-V 2.6 pair
  (`general.architecture = qwen2`, `clip.projector_type = resampler`).
* `qwen35`/`qwen35moe`/`qwen3vl` weights need a `qwen3vl_merger` projector (or `minicpmv4_6`/
  `minicpmv4_7` for MiniCPM-V 4.x). This covers Qwen3.5-VL builds such as Ternary-Bonsai.

A file whose architecture or `clip.projector_type` is outside that table is rejected immediately,
and so is a weights/projector pair from different families — a valid-looking but incompatible file
no longer reports `ready` and then fails inside llama.cpp after a multi-gigabyte load. The pick-time
scan also walks the GGUF tensor table (bounded to the first 16 MiB) and refuses a file whose tensor
types the bundled build cannot decode, which is what settles the `PQ2_0` question before any load.

Because metadata can only predict, the model card's **Test pair** button loads the selection once in
the real runtime and reports whether it works on this device. The result is a dialog with the
runtime's own log tail (copyable), not a toast: the probe shows its elapsed time while loading and a
watchdog reports a timeout after three minutes instead of leaving the button disabled forever. Diagnostics keep the two verdicts
apart: `metadata:` describes what the files claim, `runtime.pair:` is llama.cpp's own answer (or
`not tested`), and `native:` reports the loaded library, backend and ABI. A file that cannot be
memory-mapped — some SAF/FUSE descriptors reject `mmap` while allowing `read` — is retried once in
`LLAMA_LOAD_MODE_NONE` instead of failing the session.

### File packing, not just architecture

The bundled llama.cpp is pinned to upstream `77026f6e` and reads the quantisations merged there —
including Bonsai's `Q2_0` — and nothing that exists only in PrismML's fork:

| Packing | ggml tensor type | Bundled build |
|---|---|---|
| `Q2_0` (Bonsai + Hadamard) | 42 | supported |
| `Q8_0`, `Q4_K_M`, `F16`, … | standard | supported |
| `PQ2_0` | 142 | needs PrismML's binaries |
| `PTQ1_0` | 143 | needs PrismML's binaries |
| `-MTP-` drafter head | — | needs PrismML's binaries |
| `DFlash2` | — | needs PrismML's binaries |

So for Ternary-Bonsai 2 4B the pair to pick is `Ternary-Bonsai-2-4B-Q2_0.gguf` (1.21 GB) with
`Ternary-Bonsai-2-4B-mmproj-Q8_0.gguf`; a `PQ2_0-MTP` language file is the one llama.cpp refuses.
The refusal now names the offending tensor type and quotes the runtime's own log line instead of
only saying "refused the language weights", and that log tail is part of the exported diagnostics.

Earlier builds told you to copy the files to `/sdcard/Heimdall/models/`. Scoped storage made that
unreadable on Android 12+, which is why the model row stayed red; that path is no longer used or
required.

## First run

Android does not grant any of Heimdall's privileges at install time: overlay, accessibility and
screen capture are Settings toggles or per-session system dialogs. The app therefore chains the
platform screens automatically on first launch — notification permission, overlay, accessibility,
model picker, then the cast prompt — and explains each obstacle inline.

### Why nothing appeared on the screen

The overlay is only drawn when the three-layer cascade opens. Two things made that look like a bug:

* **Stage 1 could never pass on a hand-held phone.** The variance floor was `0.02 (rad/s)^2`, but a
  phone held still reads about `0.001–0.003`, so the cascade reported "resting on a desk" for the
  whole session and never reached inference. A window whose variance is under the floor but whose
  peak exceeds `0.08 rad/s` (a hand's unavoidable corrections) now counts as handheld, while a phone
  on a table — low variance *and* low peak — is still refused.
* **The pipeline was silent.** The only screen now shows a live line while a session runs: frames,
  gate passes, inferences, hints, the cascade's last refusal in words (including the measured gyro
  variance and peak), the last model verdict, and the engine state. The same facts are in the
  exported diagnostics as `capture:` / `lastGate` / `lastVerdict`, and **Show a sample hint** renders
  the production pill directly, which separates "the permission is broken" from "the cascade never
  opened".

### Accessibility is optional

Android 13+ blocks the accessibility toggle for sideloaded apps until restricted settings are
allowed, and no app can grant that to itself. Heimdall therefore treats the gesture observer as an
**optional enhancement**: without it, the cascade derives interactions from frame changes, so
capture and hints still work. Enable it for more accurate pause detection.

If the toggle answers "App was denied access", open the **main App info page** (not the Permissions
page), tap **⋮ → Allow restricted settings**, confirm, then enable the service. On builds where that
menu item is missing, the equivalent ADB commands are (release package shown; debug is
`com.heimdall.debug`):

```bash
adb shell appops set com.heimdall ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services \
  com.heimdall/com.heimdall.service.HeimdallAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

The onboarding card has a **Copy ADB command** button with these lines for the installed package.
Installing via `adb install` or a session installer does **not** exempt the app: `adb` is itself a
sideload channel, so the app-op above is still required on most builds.

Screen capture consent is per session by platform rule and cannot be persisted. Heimdall asks for it
once automatically and afterwards only when you tap Start; on Android 14+ the dialog is pinned to
the whole display so it cannot silently capture a single app.

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
