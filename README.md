# DrumSep for Android

Separates a song into **kick, snare, hi-hat, toms, cymbal (ride), crash** + the full drum kit — entirely on the phone.
No server, no PC. Internet is needed only once, to download the separation tools (110 MB).

## Get the APK

1. Push this folder to a GitHub repository (branch `main`).
2. Open the repository → **Actions** → wait for "Build DrumSep" to finish (first run ≈ 15 min, it also converts the AI model).
3. Open **Releases** → **DrumSep APK** → download `DrumSep.apk` → install on the phone.

   Direct link pattern: `https://github.com/<user>/<repo>/releases/download/apk/DrumSep.apk`

## Use

1. Open DrumSep → **Download tools** (once, 110 MB).
2. **Choose a song** (mp3, m4a, wav, flac…) or share a song to DrumSep from any app.
3. **Separate drum parts**. Leave the app if you want; it keeps working in the background.
4. Files are saved in `Music/DrumSep/<song name>/` as WAV, and can be played / shared from the app.

Phone needs ≈ 1.5 GB free RAM while working. A 4-minute song takes about 5–10 minutes in Fast mode.
Heat: choose **Cool** (2 CPU threads + rest pauses, auto-pause when hot), **Normal**, or **Max**.
Engine: CPU is the default; try XNNPACK or NPU/GPU — on some phones they are faster.

## Models

* Stage 1: `kuielab_b_drums` (MDX-Net, UVR) — drums from the full mix
* Stage 2: `MDX23C-DrumSep` by aufr33 & jarredou — kick / snare / toms / hh / ride / crash

Both are stored with 8-bit weights (4× smaller, output within −40 dB of the originals).
Fast mode processes each chunk once with a 0.19 s crossfade; Best mode averages 4 overlapping passes.

Rim shots end up inside the snare file. Conga and other percussion are not split by any public model.

## License

Developed by Surafel Kebede. © 2026 Surafel Kebede. All rights reserved.
