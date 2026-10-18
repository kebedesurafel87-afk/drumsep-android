package com.tunetech.drumsep.dsp;

import java.io.*;

/** Streams 16-bit stereo PCM WAV to an OutputStream; total length must be known up front. */
public final class WavWriter implements Closeable {
    private final OutputStream out;
    private final byte[] buf = new byte[1 << 16];

    public WavWriter(OutputStream os, int sampleRate, long totalFrames) throws IOException {
        out = new BufferedOutputStream(os, 1 << 16);
        long dataBytes = totalFrames * 4;
        ByteArrayOutputStream h = new ByteArrayOutputStream(44);
        DataOutputStream d = new DataOutputStream(h);
        d.writeBytes("RIFF"); d.writeInt(le((int) (36 + dataBytes))); d.writeBytes("WAVE");
        d.writeBytes("fmt "); d.writeInt(le(16)); d.writeShort(les(1)); d.writeShort(les(2));
        d.writeInt(le(sampleRate)); d.writeInt(le(sampleRate * 4)); d.writeShort(les(4)); d.writeShort(les(16));
        d.writeBytes("data"); d.writeInt(le((int) dataBytes));
        out.write(h.toByteArray());
    }

    private static int le(int v) { return Integer.reverseBytes(v); }
    private static int les(int v) { return Short.reverseBytes((short) v) & 0xffff; }

    /** Write n frames from planar [2][n] starting at offset. */
    public void write(float[][] planar, int off, int n) throws IOException {
        int p = 0;
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < 2; c++) {
                float v = planar[c][off + i];
                int s = Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
                buf[p++] = (byte) s; buf[p++] = (byte) (s >> 8);
            }
            if (p >= buf.length - 4) { out.write(buf, 0, p); p = 0; }
        }
        if (p > 0) out.write(buf, 0, p);
    }

    @Override public void close() throws IOException { out.flush(); out.close(); }
}
