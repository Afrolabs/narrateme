# Narrate-and-listen prototype: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Android app where a glasses capture press photographs a page and records narration through the glasses mics, and a later capture press in Listen mode plays that narration through the glasses.

**Architecture:** There are two Gradle modules.
- `core` is pure Kotlin/JVM and holds the state machine, page storage, matcher and WAV writer. It is unit-tested on the JVM.
- `app` is the Android/Compose shell. It owns the Meta DAT session, audio routing, recording and playback, and executes the controller's effects.

CI (GitHub Actions) builds the APK, because the cloud sandbox can't download the Android SDK.

**Tech Stack:**
- Kotlin 2.2.21, AGP 8.13.0, Gradle 8.14.3, compileSdk 36, minSdk 29
- Jetpack Compose (BOM 2025.09.00)
- `com.meta.wearable:mwdat-core|camera|inputs|mockdevice:1.0.1`
- JUnit 4

**Spec:** `docs/superpowers/specs/2026-11-18-narrate-listen-prototype-design.md`

## Global Constraints

- minSdk 29 and Kotlin ≥ 2.2. These are required by DAT 1.0.1, per its AAR manifest and docs.
- Manifest metadata:
  - `com.meta.wearable.mwdat.APPLICATION_ID` = `0`
  - `com.meta.wearable.mwdat.CLIENT_TOKEN` = `0`
  - Developer Mode only.
- Photo is `PhotoResolution.FULL`, `PhotoQuality.HIGH`, with one capture in flight.
- Record over Bluetooth SCO (HFP) at 16 kHz mono PCM16 WAV, falling back to 8 kHz. Release SCO before playback so audio goes over A2DP.
- Package `com.tapeaids.narrateme`.

## Review Focus

1. A capture press while a photo is already being taken. Expect it to be ignored, with no second `TakePhoto`.
2. A Listen capture when no page is stored yet. Expect a "no page" beep and a return to Idle.
3. A photo failure or recording start failure (no SCO device). Expect an error beep, a return to Idle, and no half-saved page.
4. Narrating a second time. Expect it to replace the stored page completely (photo and audio together).
5. An action button press in Listen mode or while Idle. Expect it to be ignored.

---

### Task 1: Gradle skeleton + `core` module with the controller

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, the Gradle wrapper (8.14.3)
- Create: `core/build.gradle.kts`, `core/src/main/kotlin/com/tapeaids/narrateme/core/NarrationController.kt`
- Test: `core/src/test/kotlin/com/tapeaids/narrateme/core/NarrationControllerTest.kt`

**Interfaces (produces):**
- `enum class Mode { NARRATE, LISTEN }`
- `enum class Cue { RECORDING_STARTED, SAVED, NO_PAGE, ERROR }`
- `sealed interface State`
  - `Idle`
  - `TakingPhoto`
  - `Recording(photo: ByteArray)`
  - `Playing(page: Page)`
- `sealed interface Input`
  - `CapturePressed`
  - `ActionPressed`
  - `PhotoTaken(bytes: ByteArray)`
  - `PhotoFailed(reason: String)`
  - `RecordingFailed(reason: String)`
  - `PlaybackFinished`
- `sealed interface Effect`
  - `TakePhoto`
  - `StartRecording`
  - `StopRecordingAndSave(photo: ByteArray)`
  - `Play(page: Page)`
  - `StopPlayback`
  - `Beep(cue: Cue)`
- `class NarrationController(matcher: PageMatcher)`
  - `var mode: Mode`. Setting it is ignored unless `state == Idle`.
  - `val state: State`
  - `fun on(input: Input): List<Effect>`
- `data class Page(photo: File, audio: File)` and `fun interface PageMatcher { fun match(photo: ByteArray): Page? }`. Both live in Task 2's files, but are defined here first.

- [ ] Write the tests:
  - narrate happy path: Capture → `[TakePhoto]`; PhotoTaken → `[Beep(RECORDING_STARTED), StartRecording]`; Action → `[StopRecordingAndSave(photo), Beep(SAVED)]`; state Idle
  - second capture also stops a recording
  - capture during TakingPhoto → `[]`
  - PhotoFailed → `[Beep(ERROR)]`, Idle
  - RecordingFailed during Recording → `[Beep(ERROR)]`, Idle, nothing saved
  - listen with match → `[Play(page)]`, Playing; capture while Playing → `[StopPlayback]`, Idle; PlaybackFinished → Idle
  - listen with no match → `[Beep(NO_PAGE)]`
  - Action in Listen or Idle → `[]`
  - mode change while not Idle is ignored
- [ ] Run `gradle :core:test` and check it fails.
- [ ] Implement the controller.
- [ ] Run `gradle :core:test` and check it passes.
- [ ] Commit.

### Task 2: PageStore, SinglePageMatcher, WavFileWriter

**Files:** `core/src/main/kotlin/com/tapeaids/narrateme/core/{Page.kt, PageStore.kt, WavFileWriter.kt}` + tests.

**Interfaces (produces):**
- `class PageStore(dir: File)`
  - `fun save(photo: ByteArray, recordedAudio: File): Page`. Writes `page.jpg` and moves the audio to `narration.wav`, each via a temp file and then a rename. It replaces any previous page.
  - `fun load(): Page?`. Returns null unless both files exist.
- `class SinglePageMatcher(store: PageStore) : PageMatcher`. Returns `store.load()` regardless of the photo. This is the placeholder.
- `class WavFileWriter(file: File, sampleRate: Int, channels: Int = 1) : Closeable`
  - `fun write(pcm16: ByteArray, length: Int)`
  - `close()` patches the RIFF/data sizes.

- [ ] Tests:
  - `load()` is null when empty
  - save then load round-trips the bytes
  - a second save replaces both files
  - the matcher returns null and then the page
  - the WAV header for 16000 Hz mono has byte rate 32000, block align 2, and a data size equal to the bytes written
- [ ] Implement, pass the tests, and commit.

### Task 3: Android app module

**Files:**
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/kotlin/com/tapeaids/narrateme/`
  - `MainActivity.kt` (Compose UI)
  - `NarrateViewModel.kt` (owns the controller and runs effects)
  - `glasses/GlassesSession.kt` (DAT init, registration, session, Inputs, Photo, mock mode)
  - `audio/NarrationRecorder.kt` (SCO routing + AudioRecord → WavFileWriter)
  - `audio/NarrationPlayer.kt` (release SCO, MediaPlayer media usage)
  - `audio/Beeper.kt` (ToneGenerator)
- `app/src/main/res/raw/sample_page.jpg`. This is a mock photo, copied to `filesDir` for `setCapturedPhoto(Uri)`.

**Interfaces (consumes):** the Task 1 and Task 2 APIs. It also uses these DAT 1.0.1 APIs, confirmed from the AARs:
- `Wearables.initialize(ctx)`, `registrationState`, `startRegistration(activity)`, `createSession(AutoDeviceSelector())`
- `session.start()` and `session.state` (`DeviceSessionState.STARTED`)
- `session.addInputs(InputsConfiguration(setOf(CAPTURE_BUTTON, ACTION_BUTTON)))` and `inputs.events`
- `session.addCamera()`, `camera.photo.photoStream` / `errors` / `state`, `capturePhoto(FULL, HIGH)`
- `Wearables.RequestPermissionContract()` with `Permission.CAMERA`
- `MockDeviceKit.getInstance(ctx).enable()`, `pairGlasses(GlassesModel.RAYBAN_META)` and `powerOn`/`don`/`unfold`, plus `services.cameraCapture.setCapturedPhoto(uri)` and `services.input.capture(..)` / `button(ACTION)`

UI:
- registration status with a Register button
- a "Use mock glasses" switch
- a Narrate/Listen switch, enabled only when Idle
- the state text
- a thumbnail of the stored page
- "Capture" and "Action" buttons. In mock mode these go through MockInputKit; with real glasses they feed the controller directly.

- [ ] Write the module, then commit.

### Task 4: CI build

**Files:** `.github/workflows/android.yml`
- Runs on push and pull requests.
- Uses JDK 21 and `android-actions/setup-android`.
- Runs `./gradlew :core:test :app:assembleDebug` and uploads `app-debug.apk`.

- [ ] Push, read the run, fix until green, and commit.

### Task 5: README with on-device test steps

Cover:
- Meta AI Developer Mode
- installing the APK
- registering
- granting camera and mic
- the narrate and listen steps
- mock mode
