# Narrate-and-listen prototype: design

Date: 2026-11-18 · Status: draft for David's approval

## Intent

narrateme (for tapeaids.com) lets a sighted narrator read a page aloud while wearing Meta glasses. The recording is tied to that page. Later, a visually impaired listener wearing glasses looks at the same page and hears it in the narrator's own voice.

This prototype proves the loop end to end on real hardware, with one page and one recording. The test page is a handwritten page in Sepedi. We never need to read the handwriting. Recognition means "is this the same page?", and this prototype skips even that step.

## Goals

- Narrate: press the glasses capture button. A high-res photo of the page is taken and narration recording starts through the glasses mics over Bluetooth. Press the action button to stop. The photo and audio are saved together as "the page".
- Listen: look at the page and press the capture button. The narration plays through the glasses speakers.
- Runs on Android with Ray-Ban/Oakley Meta glasses that have no display.

## Non-goals (this slice)

- Page recognition. A placeholder always matches the one stored page. A photo is still taken on listen, so the matcher can be dropped in later.
- More than one page, accounts, cloud sync, sharing between a narrator's phone and a listener's phone.
- Publishing. This runs in Meta AI Developer Mode only. The standalone Photo and Inputs APIs are beta and can't be published yet anyway.
- Display glasses and Web Apps.

## Anti-goals

- A synthetic voice. The point is the narrator's real voice.
- Needing the phone screen during use. The glasses buttons must drive the loop. The phone UI is for setup and debugging.

## Constraints (from Meta DAT docs, Nov 2026)

- Meta Wearables Device Access Toolkit 1.0 is on Maven Central (`com.meta.wearable:mwdat-core`, `mwdat-camera`, `mwdat-inputs`, `mwdat-mockdevice`). It needs Kotlin 2.2+ and Android 10+.
- Button events come through the Inputs capability (beta):
  - `Capture(pressType)` comes from `CAPTURE_BUTTON`.
  - `Button(ACTION)` comes from `ACTION_BUTTON`. The docs say the action button "has not been validated on every target device".
  - A one-finger tap and a tap-and-hold are reserved by the system (pause or stop the session).
- Photo: `camera.photo.capturePhoto(PhotoResolution.FULL, PhotoQuality.HIGH)` gives 4032×3024 JPEG/HEIC bytes. Only one capture can be in flight at a time. The docs call it less reliable than streaming.
- Glasses mic: it uses HFP (Bluetooth SCO, mono, 16 or 8 kHz). Route it with `AudioManager.setCommunicationDevice(TYPE_BLUETOOTH_SCO)` in `MODE_IN_COMMUNICATION` and record with `AudioRecord`.
- HFP and A2DP are mutually exclusive. While recording, the glasses are in call-quality mode. Playback should drop HFP so it goes out over A2DP (44.1/48 kHz stereo).
- Developer Mode: only one third-party app can be registered at a time. `APPLICATION_ID` and `CLIENT_TOKEN` can be `0`.
- Mock Device Kit can fake a paired Ray-Ban Meta, a captured photo and capture-button presses. That lets most of the app be exercised without glasses.

## Approaches considered

1. **Glasses mic over HFP + standalone Photo (chosen by David).**
   - Upside: this is exactly the requested flow, and the APIs are simple.
   - Cost: voice is call quality (16/8 kHz).
2. **Glasses mic through the camera stream's in-stream audio (beta).**
   - Upside: 16–48 kHz PCM, and the speakers stay on A2DP.
   - Cost: it needs a video stream running while recording. Stream and Photo can't run at once, so it would mean photo first, then the stream.
   - Worth trying later if HFP quality is too poor for listeners.
3. **Phone mic for recording.**
   - Upside: best quality.
   - Cost: it breaks the hands-free, glasses-first feel. David chose the glasses mics.

## Design

One Android app (`app/`, Kotlin, Jetpack Compose) with these pieces:

- **GlassesSession**
  - Initialises Wearables, handles registration (the deeplink to Meta AI), creates and starts the device session, and adds Inputs and Camera.
  - Exposes button events and a `capturePhoto()` call.
  - A debug toggle swaps in Mock Device Kit.
- **NarrationRecorder**
  - Routes input to the glasses' Bluetooth SCO device and records with `AudioRecord` to a WAV file. WAV is my call because it's simple and lossless at this sample rate.
  - On stop, it clears the communication device and sets `MODE_NORMAL`.
- **NarrationPlayer**
  - Plays the WAV with `MediaPlayer` after making sure HFP is released, so audio goes over A2DP to the glasses.
- **PageStore**
  - One page in app-private storage: `page.jpg` plus `narration.wav`.
- **PageMatcher (placeholder)**
  - `match(photo) -> Page?`. It returns the stored page whenever one exists.
  - It's an interface, so a real image matcher can replace it.
- **Controller (state machine)**
  - Mode is chosen on the phone: Narrate or Listen.
  - Narrate:
    - Idle → capture press → photo → Recording.
    - Recording → action button → Saved.
    - Fallback: because the action button isn't validated on every model, a second capture press also stops the recording. That's my call.
  - Listen:
    - Idle → capture press → photo → match → Playing.
    - Playing → capture press → stop.
  - Audible cues are short beeps through the glasses for "recording started", "saved" and "no page". This is my call, since the listener can't see the phone.
- **Phone UI**
  - Shows the connection and registration status, the mode switch and current state, a thumbnail of the stored page, and on-screen buttons that mirror the glasses buttons for testing.

## Left to the builder

- Exact Compose layout, package names, and the beep sounds.
- Retry policy for photo `SETUP_FAILED` and `CAPTURE_FAILURE`. The docs suggest a bounded retry with a fresh `addCamera()`.
- Whether the capture button also triggers the glasses' own system photo. The docs don't say, so check it on the device.

## Verification

- Unit tests for the controller state machine and PageStore.
- `./gradlew assembleDebug` and the tests run in CI or the cloud. No glasses are available there.
- David runs it on his Android phone with his glasses in Developer Mode.
