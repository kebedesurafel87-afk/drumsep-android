package com.tunetech.drumsep.dsp;

/** In-place iterative radix-2 complex FFT (power-of-two sizes) with real-signal helpers. */
public final class FFT {
    private final int n, log2n;
    private final float[] cos, sin;
    private final int[] rev;
    private final float[] re, im;

    public FFT(int n) {
        if (Integer.bitCount(n) != 1) throw new IllegalArgumentException("n must be a power of 2");
        this.n = n;
        this.log2n = Integer.numberOfTrailingZeros(n);
        cos = new float[n / 2];
        sin = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double a = -2.0 * Math.PI * i / n;
            cos[i] = (float) Math.cos(a);
            sin[i] = (float) Math.sin(a);
        }
        rev = new int[n];
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - log2n);
        re = new float[n];
        im = new float[n];
    }

    public int size() { return n; }

    private void transform(boolean inverse) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1, step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < half; k++) {
                    float wr = cos[k * step], wi = inverse ? -sin[k * step] : sin[k * step];
                    int a = i + k, b = a + half;
                    float xr = re[b] * wr - im[b] * wi;
                    float xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr;        im[a] += xi;
                }
            }
        }
    }

    /**
     * Real forward FFT. in: n samples (already windowed). Writes bins 0..nBins-1
     * into outRe/outIm at the given offsets, with stride between consecutive bins.
     */
    public void rfft(float[] in, float[] outRe, int reOff, float[] outIm, int imOff, int nBins, int stride) {
        System.arraycopy(in, 0, re, 0, n);
        java.util.Arrays.fill(im, 0f);
        transform(false);
        for (int k = 0; k < nBins; k++) {
            outRe[reOff + k * stride] = re[k];
            outIm[imOff + k * stride] = im[k];
        }
    }

    /**
     * Inverse real FFT from a Hermitian half-spectrum. Bins nBins..n/2 are treated as zero.
     * Result (n real samples, already divided by n) is written to out.
     */
    public void irfft(float[] specRe, int reOff, float[] specIm, int imOff, int nBins, int stride, float[] out) {
        java.util.Arrays.fill(re, 0f);
        java.util.Arrays.fill(im, 0f);
        int half = n / 2;
        for (int k = 0; k < nBins && k <= half; k++) {
            re[k] = specRe[reOff + k * stride];
            im[k] = specIm[imOff + k * stride];
        }
        im[0] = 0f;                       // DC and Nyquist must be real
        if (nBins > half) im[half] = 0f;
        for (int k = 1; k < half; k++) {  // mirror
            re[n - k] = re[k];
            im[n - k] = -im[k];
        }
        transform(true);
        float inv = 1f / n;
        for (int i = 0; i < n; i++) out[i] = re[i] * inv;
    }
}
