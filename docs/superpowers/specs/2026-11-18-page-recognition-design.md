# Page recognition and spoken prompts: short design

Approved by David on 2026-11-18. He asked to keep it small: "start on a few, not shave the yak".

## Intent

A listener looks at one of a few narrated pages and presses capture. The app works out which page it is and plays that page's narration. The wearer may not see the phone, so every step is spoken aloud.

## Approaches

- **On-phone OpenCV feature matching (chosen).** ORB keypoints, a ratio test, then a RANSAC homography inlier count. It works offline, needs no key, copes with a different angle, distance and light, and is good at telling apart pages that look alike. It adds roughly 20–30 MB per ABI.
- **Cloud vision LLM.** Needs a network connection and an API key. Too much for now.
- **Image-embedding similarity (ML Kit or MediaPipe).** Poor at separating near-identical lined, handwritten pages.

## What changes

1. **Many pages.** `PageStore` keeps one folder per narration. The screen lists the pages and has a "Clear all" button.
2. **Listen.** Photo, then `FindPage`. The recogniser scores every page, and `pickMatch` accepts the best one only if it scores at least 25 inliers and at least 1.5x the runner-up. Then it says "Page recognised, starting narration" and plays it, or says "Page not recognised".
3. **Spoken prompts replace the beeps** (Android TTS, English):
   - "Recording started"
   - "Narration saved"
   - "Page recognised, starting narration"
   - "Page not recognised"
   - "Photo failed, please try again"
   - "Recording failed"
4. **Tuning aid.** The match scores show on screen after each listen.

## Out of scope for now

- Naming pages
- Deleting single pages
- Sepedi prompts
- Migrating the single page saved by v0.1 (narrate it again)
