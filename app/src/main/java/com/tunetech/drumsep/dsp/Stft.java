package com.tunetech.drumsep.dsp;

/**
 * STFT / ISTFT with the exact conventions of torch.stft(center=True, pad_mode='reflect',
 * periodic Hann window) as used by UVR / audio-separator.
 *
 * Spectrogram layout (flat float[]): [channel*2 + (0=re,1=im)][bin][frame]
 * i.e. index = ((c*2 + ri) * dimF + f) * frames + t
 */
public final class Stft {
    public final int nFft, hop, dimF;
    private final FFT fft;
    private final float[] window;   // periodic Hann
    private final float[] seg, tmp;

    public Stft(int nFft, int hop, int dimF) {
        this.nFft = nFft;
        this.hop = hop;
        this.dimF = dimF;
        fft = new FFT(nFft);
        window = new float[nFft];
        for (int i = 0; i < nFft; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / nFft));
        seg = new float[nFft];
        tmp = new float[nFft];
    }

    public int frames(int length) { return 1 + length / hop; }   // centre-padded by nFft/2 each side

    /** x: [2][length] -> spectrogram float[4 * dimF * frames]. */
    public float[] forward(float[][] x) {
        int length = x[0].length;
        int pad = nFft / 2;
        int frames = frames(length);
        float[] out = new float[4 * dimF * frames];
        for (int c = 0; c < 2; c++) {
            float[] ch = x[c];
            int reBase = (c * 2) * dimF * frames;
            int imBase = (c * 2 + 1) * dimF * frames;
            for (int t = 0; t < frames; t++) {
                int start = t * hop - pad;
                for (int i = 0; i < nFft; i++) {
                    int idx = start + i;
                    // reflect padding (torch 'reflect': no edge repeat)
                    if (idx < 0) idx = -idx;
                    else if (idx >= length) idx = 2 * (length - 1) - idx;
                    seg[i] = ch[idx] * window[i];
                }
                fft.rfft(seg, out, reBase + t, out, imBase + t, dimF, frames);
            }
        }
        return out;
    }

    /**
     * Inverse of forward(). spec holds (at least) 4*dimF*frames floats starting at offset.
     * Returns [2][length], length = hop*(frames-1).
     */
    public float[][] inverse(float[] spec, int offset, int frames) {
        int length = hop * (frames - 1);
        int total = nFft + length;
        int pad = nFft / 2;
        float[][] out = new float[2][length];
        float[] acc = new float[total];
        float[] wsum = new float[total];
        for (int t = 0; t < frames; t++)
            for (int i = 0; i < nFft; i++) wsum[t * hop + i] += window[i] * window[i];
        for (int c = 0; c < 2; c++) {
            java.util.Arrays.fill(acc, 0f);
            int reBase = offset + (c * 2) * dimF * frames;
            int imBase = offset + (c * 2 + 1) * dimF * frames;
            for (int t = 0; t < frames; t++) {
                fft.irfft(spec, reBase + t, spec, imBase + t, dimF, frames, tmp);
                int s = t * hop;
                for (int i = 0; i < nFft; i++) acc[s + i] += tmp[i] * window[i];
            }
            for (int i = 0; i < length; i++) {
                float w = wsum[pad + i];
                out[c][i] = w > 1e-11f ? acc[pad + i] / w : acc[pad + i];
            }
        }
        return out;
    }
}
