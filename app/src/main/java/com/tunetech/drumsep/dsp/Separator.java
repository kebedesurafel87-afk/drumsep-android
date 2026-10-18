package com.tunetech.drumsep.dsp;

import java.io.IOException;
import java.util.Arrays;

/**
 * The two separation stages, streamed so memory use is constant regardless of song length.
 *
 *  Stage 1  MDX-Net "kuielab_b_drums"  : full mix  -> drums        (input 1x4x2048x128, n_fft 4096, hop 1024)
 *  Stage 2  MDX23C "DrumSep"           : drums     -> 6 drum parts (input 1x4x1024x256, n_fft 2048, hop 512)
 *
 * The arithmetic mirrors python-audio-separator exactly (verified against it).
 */
public final class Separator {
    public static final String[] STEMS = {"kick", "snare", "toms", "hh", "ride", "crash"};

    /** Receives final samples for stem k (planar [2][n]) in order, start to end. */
    public interface StemSink {
        void write(int stem, float[][] planar, int off, int n) throws IOException;
    }

    // ------------------------------------------------------------------ stage 1
    public static final int S1_NFFT = 4096, S1_HOP = 1024, S1_DIMF = 2048, S1_DIMT = 128;

    public static void stage1(FloatTrack mix, FloatTrack drumsOut, ModelRunner model, Progress progress) throws Exception {
        final int chunk = S1_HOP * (S1_DIMT - 1);      // 130048
        final int trim = S1_NFFT / 2;                    // 2048
        final int gen = chunk - 2 * trim;
        final int step = (int) ((1 - 0.25) * chunk);     // 97536
        final long L = mix.frames();
        final int pad = (int) (gen + trim - Math.floorMod(L, (long) gen));
        final long M = trim + L + pad;                   // virtual padded length
        final int nChunks = (int) ((M + step - 1) / step);

        mix.computePeak();
        final float peak = mix.peak();
        final float scale = peak > 0.9f ? 0.9f / peak : 1f;

        Stft stft = new Stft(S1_NFFT, S1_HOP, S1_DIMF);
        float[][] part = new float[2][chunk];
        float[][] acc = new float[2][chunk];
        float[] div = new float[chunk];
        float[][] flush = new float[2][step];
        long written = 0;

        for (int ci = 0; ci < nChunks; ci++) {
            long start = (long) ci * step;
            long end = Math.min(start + chunk, M);
            int actual = (int) (end - start);
            // mixture[m] = mix[m - trim] (zero outside) ; chunk zero-padded to full size
            mix.read(start - trim, chunk, part);
            if (actual < chunk) { Arrays.fill(part[0], actual, chunk, 0f); Arrays.fill(part[1], actual, chunk, 0f); }
            if (scale != 1f) for (int c = 0; c < 2; c++) for (int i = 0; i < chunk; i++) part[c][i] *= scale;

            float[] spec = stft.forward(part);
            int frames = S1_DIMT;
            for (int c4 = 0; c4 < 4; c4++)              // zero first 3 bins
                for (int f = 0; f < 3; f++)
                    Arrays.fill(spec, (c4 * S1_DIMF + f) * frames, (c4 * S1_DIMF + f) * frames + frames, 0f);
            float[] pred = model.run(spec);
            float[][] wav = stft.inverse(pred, 0, frames);

            // symmetric Hann (numpy.hanning) over the actual chunk length
            for (int i = 0; i < actual; i++) {
                float w = actual == 1 ? 1f : (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (actual - 1)));
                acc[0][i] += wav[0][i] * w;
                acc[1][i] += wav[1][i] * w;
                div[i] += w;
            }
            // samples [start, start+step) are now final
            int nFlush = (int) Math.min(step, M - start);
            for (int c = 0; c < 2; c++)
                for (int i = 0; i < nFlush; i++) flush[c][i] = div[i] > 0 ? acc[c][i] / div[i] / scale : 0f;
            // map mixture index -> drums index (subtract trim), clip to [0, L)
            long from = Math.max(start, trim), to = Math.min(start + nFlush, trim + L);
            if (to > from) {
                int off = (int) (from - start), n = (int) (to - from);
                float[][] blk = new float[2][n];
                System.arraycopy(flush[0], off, blk[0], 0, n);
                System.arraycopy(flush[1], off, blk[1], 0, n);
                drumsOut.append(blk, n);
                written += n;
            }
            // shift accumulators left by step
            for (int c = 0; c < 2; c++) {
                System.arraycopy(acc[c], step, acc[c], 0, chunk - step);
                Arrays.fill(acc[c], chunk - step, chunk, 0f);
            }
            System.arraycopy(div, step, div, 0, chunk - step);
            Arrays.fill(div, chunk - step, chunk, 0f);
            if (progress != null && !progress.onProgress(ci + 1, nChunks)) throw new InterruptedException("cancelled");
        }
        if (written != L) throw new IllegalStateException("stage1 wrote " + written + " of " + L);
    }

    // ------------------------------------------------------------------ stage 2
    public static final int S2_NFFT = 2048, S2_HOP = 512, S2_DIMF = 1024, S2_DIMT = 256;

    public static void stage2(FloatTrack drums, int overlap, ModelRunner model, StemSink sink, Progress progress) throws Exception {
        final int chunk = S2_HOP * (S2_DIMT - 1);       // 130560
        final int hop = chunk / overlap;
        final long L = drums.frames();
        final int pad = (int) (hop - Math.floorMod(L - chunk, (long) hop));
        final long lead = chunk - hop;                   // zeros before the song
        final long total = lead + L + pad + lead;        // padded length
        final int nChunks = (int) ((total - chunk) / hop) + 1;

        Stft stft = new Stft(S2_NFFT, S2_HOP, S2_DIMF);
        float[][] part = new float[2][chunk];
        float[][][] acc = new float[6][2][chunk];
        float[][] flush = new float[2][hop];
        int frames = S2_DIMT;
        int stemStride = 4 * S2_DIMF * frames;
        long[] written = new long[6];

        for (int ci = 0; ci < nChunks; ci++) {
            long s = (long) ci * hop;                    // start in padded coordinates
            drums.read(s - lead, chunk, part);           // padded[p] = drums[p - lead]
            float[] spec = stft.forward(part);
            float[] pred = model.run(spec);              // 24 x dimF x frames
            for (int k = 0; k < 6; k++) {
                float[][] wav = stft.inverse(pred, k * stemStride, frames);
                for (int c = 0; c < 2; c++)
                    for (int i = 0; i < chunk; i++) acc[k][c][i] += wav[c][i];
            }
            // padded indices [s, s+hop) are final
            long from = Math.max(s, lead), to = Math.min(s + hop, lead + L);
            for (int k = 0; k < 6; k++) {
                if (to > from) {
                    int off = (int) (from - s), n = (int) (to - from);
                    for (int c = 0; c < 2; c++)
                        for (int i = 0; i < n; i++) flush[c][i] = acc[k][c][off + i] / overlap;
                    sink.write(k, flush, 0, n);
                    written[k] += n;
                }
                for (int c = 0; c < 2; c++) {
                    System.arraycopy(acc[k][c], hop, acc[k][c], 0, chunk - hop);
                    Arrays.fill(acc[k][c], chunk - hop, chunk, 0f);
                }
            }
            if (progress != null && !progress.onProgress(ci + 1, nChunks)) throw new InterruptedException("cancelled");
        }
        for (int k = 0; k < 6; k++)
            if (written[k] != L) throw new IllegalStateException("stage2 stem " + k + " wrote " + written[k] + " of " + L);
    }
}
