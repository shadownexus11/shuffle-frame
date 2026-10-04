# Shuffle

A minimal Android slideshow that shuffle-plays the photos in one folder.

- Pick a folder once; it's remembered.
- Every photo is shown once per round (shuffled deck), then reshuffled.
- Fitted photo over a blurred backdrop, Ken Burns pan-and-zoom, crossfade / slide / zoom transitions.
- Tap to show controls, swipe left/right to skip.

## Getting the app

Every push to `main` builds a signed APK automatically (see the **Actions** tab).
The newest APK is attached to the latest entry under **Releases**. Open that page on
the phone, download the `.apk`, and open it to install or update.

The signing key lives in `keystore/` so every build can update the previous one.
That is acceptable only because this repository is private and the app is personal.

## Built-in music

The four built-in tracks (Drift, Lantern, Tide, Morning) are original pieces synthesised
from scratch by `tools/compose_music.py` (no samples, no licences). Each is built to loop
seamlessly. To regenerate: `python3 tools/compose_music.py app/src/main/res/raw`
(needs numpy, scipy and ffmpeg).
