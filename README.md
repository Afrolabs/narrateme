# narrateme

A narration app for [tapeaids.com](https://tapeaids.com).

A sighted narrator points a phone (or wears Meta glasses), looks at a page and reads it aloud. Later, a visually impaired listener points the phone at the same page and hears it in the narrator's own voice.

The phone alone is enough: its camera, mic, speaker and buttons do everything. Meta glasses are an optional, hands-free upgrade that stand in for the camera, mic and buttons.

This is a prototype. It stores any number of narrated pages and recognises which one you're looking at, on the phone and offline. See the [design](docs/superpowers/specs/2026-11-18-narrate-listen-prototype-design.md) and the [plan](docs/superpowers/plans/2026-11-18-narrate-listen-prototype.md).

## How it works

It's an Android app (Kotlin, Jetpack Compose). The glasses support uses Meta's [Wearables Device Access Toolkit](https://wearables.developer.meta.com/docs/develop/dat) 1.0.1, with Ray-Ban Meta or Oakley Meta glasses (no display needed).

**Listen** (the main screen) is one full-screen **Listen to pages** button.
- Tap it and hold the phone over a page. When the view holds steady on something with writing on it, the phone snaps a photo by itself and looks the page up.
- A match plays a page-turn sound and reads the page. Otherwise it says "Page not recognised" and keeps looking. (Planned: after the page-turn sound, point one finger at a part of the page to hear it, or hold up two fingers for the whole page.)
- When a narration ends it waits for the next page: turn the page, hold still, and it snaps again. The page it just read isn't snapped twice.
- Tap anywhere to pause or carry on. Volume up skips to the page now in view. Back or volume down stops listening.

**Record narration** (from the ☰ menu) works like a camera app, for the narrator. Aim at the page and press the shutter (or volume up). The photo then fills the screen with a live waveform of your voice over it while you read the page aloud. Press the red stop button (or volume down) to save. The thumbnail in the corner opens the saved pages.

**Glasses** (from the ☰ menu) switches the camera, mic and buttons over to the glasses: capture snaps a page, action stops. It also has registration, connection and mock mode. Narration and prompts play in the glasses whenever they're connected over Bluetooth, whichever device is chosen.

Code layout:

- `core/` is plain Kotlin with unit tests:
  - the narrate/listen state machine (`NarrationController`)
  - `SnapDetector`, which decides from small greyscale frames when a new page is being held steady
  - `PageStore` (one folder per page), `pickMatch` (decides whether the best score is convincing) and `WavFileWriter`
- `app/` is the Android shell:
  - `NarrationDevice` is the camera, mic and buttons. `PhoneDevice` uses CameraX (preview, photo, frame analysis for auto-snap). `GlassesSession` uses the toolkit: registration, session, buttons, photos and mock mode.
  - `NarrationRecorder` records from the phone mic, or the glasses mics over Bluetooth SCO.
  - `NarrationPlayer` plays (and pauses) the narration on the media stream. `PageTurnSound` plays the snap sound (synthesised by `scripts/make_page_turn.py`).
  - `Speaker` speaks the prompts with Android text-to-speech (English).
  - `PageRecognizer` scores a photo against each saved page with OpenCV: ORB keypoints, a ratio test, then counting matches that agree on one perspective transform (RANSAC homography). The listen screen shows the scores, to help tune the threshold (25, and 1.5x the runner-up).

## Build

```bash
./gradlew :core:test :app:assembleDebug
```

CI builds the debug APK on every push. Download it from the **narrateme-debug-apk** artifact on the Actions run.

You can run just the core tests without the Android SDK: `./gradlew -PcoreOnly :core:test`.

## Try it on your phone

1. Install the APK on your Android phone (Android 10+) and allow **Camera** and **Microphone**.
2. ☰ → **Record narration**. Aim at a page, press the shutter, read it aloud, press stop. Do a few pages.
3. Back on the main screen, tap **Listen to pages** and hold the phone over one of them.

## Try it with glasses

1. **Meta AI app:** pair your glasses. Then go to Settings → App Info, tap the app version 5 times, and turn on **Developer Mode**.
2. Install the APK on your Android phone (Android 10+).
3. Open narrateme, allow **Microphone** and **Nearby devices** (Bluetooth), then ☰ → **Glasses** and turn on **Use the glasses instead of the phone**.
4. Tap **Register**. The Meta AI app opens. Approve narrateme, and it returns here.
5. Tap **Connect**, then tap **Camera permission** and allow it in Meta AI.
6. **Narrate:** ☰ → **Record narration** and look at the page. Press the glasses **capture button**. After "Recording started", read the page aloud. Press the **action button**, or capture again, to finish.
7. **Listen:** on the main screen tap **Listen to pages** (or press capture). It snaps what you're looking at straight away. Its narration plays in the glasses. For the next page, press **capture** again.

On the Record narration screen, the on-screen shutter and stop buttons do the same as the glasses buttons, if a button doesn't reach the app.

### Without glasses

Turn on **Use mock glasses** before connecting. This uses Meta's Mock Device Kit:
- a simulated Ray-Ban Meta
- a sample page image as the "photo"
- on-screen buttons that act as glasses presses

Recording still needs a Bluetooth headset mic, so with no headset you'll hear "Recording failed". That's expected.

## Things to check on real hardware

- **Auto-snap tuning (phone).** `SnapDetector` thresholds are first guesses: how long "steady" is, how different a view must be to count as a new page. If it snaps too eagerly, or misses a page turn, those are the knobs.

- **The capture button.** Does pressing it also take a normal glasses photo or video? Meta's docs don't say.
- **The action button.** Meta says it "has not been validated on every target device". A second capture press is the fallback.
- **Voice quality.** The glasses mics record call-quality audio (16 or 8 kHz mono over HFP). If that's too rough for listeners, try the camera stream's in-stream audio next (up to 48 kHz, beta).
- **Photo capture.** The standalone photo API failed on Oakley Meta, so the app briefly starts the camera stream and captures a frame from it.
- **Speaker playback.** Playback should switch back to high-quality Bluetooth (A2DP) after recording ends.
