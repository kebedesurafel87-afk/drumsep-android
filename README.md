# DrumSep for Android

Separates a song into **kick, snare, hi-hat, toms, cymbal (ride), crash** + the full drum kit — entirely on the phone.
No server, no PC. Internet is needed only once, to download the AI models (440 MB).

## Get the APK

1. Push this folder to a GitHub repository (branch `main`).
2. Open the repository → **Actions** → wait for "Build DrumSep" to finish (first run ≈ 15 min, it also converts the AI model).
3. Open **Releases** → **DrumSep APK** → download `DrumSep.apk` → install on the phone.

   Direct link pattern: `https://github.com/<user>/<repo>/releases/download/apk/DrumSep.apk`

## Use

1. Open DrumSep → **Download models** (once, 440 MB, Wi-Fi recommended).
2. **Choose a song** (mp3, m4a, wav, flac…) or share a song to DrumSep from any app.
3. **Separate drum parts**. Leave the app if you want; it keeps working in the background.
4. Files are saved in `Music/DrumSep/<song name>/` as WAV, and can be played / shared from the app.

Phone needs ≈ 2 GB free RAM while working (the model is 418 MB). A 4-minute song takes 5–15 minutes.

## Models

* Stage 1: `kuielab_b_drums` (MDX-Net, UVR) — drums from the full mix
* Stage 2: `MDX23C-DrumSep` by aufr33 & jarredou — kick / snare / toms / hh / ride / crash

Rim shots end up inside the snare file. Conga and other percussion are not split by any public model.
