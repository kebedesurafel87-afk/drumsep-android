package com.tunetech.drumsep;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import com.tunetech.drumsep.dsp.FloatTrack;
import com.tunetech.drumsep.dsp.Separator;
import com.tunetech.drumsep.dsp.Stft;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/** Quick diagnostic: decodes 20 s of the song, runs one chunk through each network and reports signal levels. */
public final class SelfTest {

    public static String run(Context ctx, Uri uri, int engine) {
        StringBuilder sb = new StringBuilder();
        sb.append("DrumSep self-test  v").append(BuildConfig.VERSION_NAME).append('\n');
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("  Android ").append(Build.VERSION.RELEASE)
          .append("  abi ").append(Build.SUPPORTED_ABIS[0]).append("  cores ").append(Runtime.getRuntime().availableProcessors())
          .append(" big ").append(SeparationService.bigCores()).append('\n');
        File cache = new File(ctx.getCacheDir(), "selftest"); cache.mkdirs();
        File f = new File(cache, "mix.f32");
        try {
            long t0 = System.currentTimeMillis();
            AudioDecoder.Info info = new AudioDecoder.Info();
            FloatTrack mix = AudioDecoder.decode(ctx, uri, f, null, 20 * AudioDecoder.SR, info);
            sb.append(String.format(Locale.US, "decode: %s %d Hz %d ch pcm=%d -> %d frames (%.1f s) in %d ms\n",
                    info.mime, info.sampleRate, info.channels, info.pcmEncoding, mix.frames(), mix.frames() / 44100.0,
                    System.currentTimeMillis() - t0));
            int chunk1 = Separator.S1_HOP * (Separator.S1_DIMT - 1);
            int chunk2 = Separator.S2_CHUNK;
            float[][] part = new float[2][chunk1];
            long start = Math.max(0, Math.min(mix.frames() - chunk1, 8L * AudioDecoder.SR));
            mix.read(start, chunk1, part);
            sb.append("input chunk  : ").append(level(part, chunk1)).append('\n');

            // ---- stage 1
            if (!ModelManager.isReady(ctx, ModelManager.DRUMS)) { sb.append("tool 1 not downloaded\n"); return sb.toString(); }
            t0 = System.currentTimeMillis();
            Stft st1 = new Stft(Separator.S1_NFFT, Separator.S1_HOP, Separator.S1_DIMF);
            float[] spec = st1.forward(part);
            sb.append("spec1        : ").append(stats(spec)).append('\n');
            float[][] drums;
            try (OnnxRunner m1 = new OnnxRunner(ModelManager.file(ctx, ModelManager.DRUMS).getPath(),
                    new long[]{1, 4, Separator.S1_DIMF, Separator.S1_DIMT}, 2, engine)) {
                long t1 = System.currentTimeMillis();
                float[] pred = m1.run(spec);
                sb.append(String.format(Locale.US, "model1 (%s): %d ms, out %s\n", m1.engineUsed, System.currentTimeMillis() - t1, stats(pred)));
                drums = st1.inverse(pred, 0, Separator.S1_DIMT);
            }
            sb.append("drums chunk  : ").append(level(drums, chunk1)).append('\n');

            // ---- stage 2
            if (!ModelManager.isReady(ctx, ModelManager.DRUMSEP)) { sb.append("tool 2 not downloaded\n"); return sb.toString(); }
            float[][] d2 = new float[2][chunk2];
            for (int c = 0; c < 2; c++) System.arraycopy(drums[c], 0, d2[c], 0, Math.min(chunk1, chunk2));
            Stft st2 = new Stft(Separator.S2_NFFT, Separator.S2_HOP, Separator.S2_DIMF);
            float[] spec2 = st2.forward(d2);
            try (OnnxRunner m2 = new OnnxRunner(ModelManager.file(ctx, ModelManager.DRUMSEP).getPath(),
                    new long[]{1, 4, Separator.S2_DIMF, Separator.S2_DIMT}, 2, engine)) {
                long t1 = System.currentTimeMillis();
                float[] pred = m2.run(spec2);
                sb.append(String.format(Locale.US, "model2 (%s): %d ms, out %s\n", m2.engineUsed, System.currentTimeMillis() - t1, stats(pred)));
                int stride = 4 * Separator.S2_DIMF * Separator.S2_DIMT;
                for (int k = 0; k < 6; k++) {
                    float[][] w = st2.inverse(pred, k * stride, Separator.S2_DIMT);
                    sb.append(String.format(Locale.US, "  %-6s : %s\n", Separator.STEMS[k], level(w, chunk2)));
                }
            }
            mix.close();
        } catch (Throwable e) {
            sb.append("ERROR ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
            StackTraceElement[] tr = e.getStackTrace();
            for (int i = 0; i < Math.min(4, tr.length); i++) sb.append("   at ").append(tr[i]).append('\n');
        } finally {
            f.delete();
        }
        return sb.toString();
    }

    /** peak and RMS in dBFS of a stereo block. */
    static String level(float[][] x, int n) {
        double peak = 0, sum = 0; int nan = 0;
        for (int c = 0; c < 2; c++) for (int i = 0; i < n; i++) {
            float v = x[c][i];
            if (Float.isNaN(v) || Float.isInfinite(v)) { nan++; continue; }
            peak = Math.max(peak, Math.abs(v)); sum += v * v;
        }
        double rms = Math.sqrt(sum / (2.0 * n));
        return String.format(Locale.US, "peak %6.1f dB  rms %6.1f dB%s", db(peak), db(rms), nan > 0 ? "  NaN=" + nan : "");
    }

    static String stats(float[] a) {
        double mx = 0, sum = 0; int nan = 0;
        for (float v : a) { if (Float.isNaN(v) || Float.isInfinite(v)) { nan++; continue; } mx = Math.max(mx, Math.abs(v)); sum += v * v; }
        return String.format(Locale.US, "n=%d max %.3g rms %.3g%s", a.length, mx, Math.sqrt(sum / a.length), nan > 0 ? " NaN=" + nan : "");
    }

    static double db(double v) { return 20 * Math.log10(Math.max(v, 1e-9)); }
}
