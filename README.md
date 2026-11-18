# narrateme

A narration app for [tapeaids.com](https://tapeaids.com).

A sighted narrator wearing Meta glasses looks at a page and reads it aloud. Later, a visually impaired listener wearing glasses looks at the same page and hears it in the narrator's own voice.

This is a prototype. It stores any number of narrated pages and recognises which one you're looking at, on the phone and offline. See the [design](docs/superpowers/specs/2026-11-18-narrate-listen-prototype-design.md) and the [plan](docs/superpowers/plans/2026-11-18-narrate-listen-prototype.md).

## How it works

It's an Android app (Kotlin, Jetpack Compose) built on Meta's [Wearables Device Access Toolkit](https://wearables.developer.meta.com/docs/develop/dat) 1.0.1. It works with Ray-Ban Meta or Oakley Meta glasses, and doesn't need a display.

| Mode | Glasses | What happens |
|---|---|---|
| Narrate | Press **capture** | A photo of the page is taken, the glasses say "Recording started", and recording starts through the glasses mics (Bluetooth HFP). |
| Narrate | Press **action** (or capture again) | Recording stops. The photo and narration are saved as a new page, and the glasses say "Narration saved". |
| Listen | Press **capture** | A photo is taken and compared with every saved page. If one matches, the glasses say "Page recognised, starting narration" and play it. Otherwise they say "Page not recognised". |
| Listen | Press **capture** while playing | Playback stops. |

Code layout:

- `core/` is plain Kotlin with unit tests. It holds the narrate/listen state machine (`NarrationController`), `PageStore` (one folder per page), `pickMatch` (decides whether the best score is convincing) and `WavFileWriter`.
- `app/` is the Android shell:
  - `GlassesSession` covers registration, the session, buttons, photos and mock mode.
  - `NarrationRecorder` covers SCO routing and `AudioRecord`.
  - `NarrationPlayer` plays the narration over A2DP.
  - `Speaker` speaks the prompts with Android text-to-speech (English).
  - `PageRecognizer` scores a photo against each saved page with OpenCV: ORB keypoints, a ratio test, then counting matches that agree on one perspective transform (RANSAC homography). The screen shows the scores after each listen, to help tune the threshold (25, and 1.5x the runner-up).

## Build

```bash
./gradlew :core:test :app:assembleDebug
```

CI builds the debug APK on every push. Download it from the **narrateme-debug-apk** artifact on the Actions run.

You can run just the core tests without the Android SDK: `./gradlew -PcoreOnly :core:test`.

## Try it on your phone and glasses

1. **Meta AI app:** pair your glasses. Then go to Settings → App Info, tap the app version 5 times, and turn on **Developer Mode**.
2. Install the APK on your Android phone (Android 10+).
3. Open narrateme and allow **Microphone** and **Nearby devices** (Bluetooth).
4. Tap **Register**. The Meta AI app opens. Approve narrateme, and it returns here.
5. Tap **Connect**, then tap **Camera permission** and allow it in Meta AI.
6. **Narrate:** choose *Narrate* and look at the page. Press the glasses **capture button**. After "Recording started", read the page aloud. Press the **action button**, or capture again, to finish.
7. **Listen:** choose *Listen*, look at one of the narrated pages, and press **capture**. Its narration plays in the glasses. The match scores show on the phone.

The on-screen **Capture** and **Action** buttons do the same as the glasses buttons, if a button doesn't reach the app.

### Without glasses

Turn on **Use mock glasses** before connecting. This uses Meta's Mock Device Kit:
- a simulated Ray-Ban Meta
- a sample page image as the "photo"
- on-screen buttons that act as glasses presses

Recording still needs a Bluetooth headset mic, so with no headset you'll hear "Recording failed". That's expected.

## Things to check on real hardware

- **The capture button.** Does pressing it also take a normal glasses photo or video? Meta's docs don't say.
- **The action button.** Meta says it "has not been validated on every target device". A second capture press is the fallback.
- **Voice quality.** The glasses mics record call-quality audio (16 or 8 kHz mono over HFP). If that's too rough for listeners, try the camera stream's in-stream audio next (up to 48 kHz, beta).
- **Photo capture.** The standalone photo API failed on Oakley Meta, so the app briefly starts the camera stream and captures a frame from it.
- **Speaker playback.** Playback should switch back to high-quality Bluetooth (A2DP) after recording ends.
