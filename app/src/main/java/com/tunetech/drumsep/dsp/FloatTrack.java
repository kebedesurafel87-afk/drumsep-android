package com.tunetech.drumsep.dsp;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;

/**
 * Stereo float32 PCM stored in a raw file (interleaved L R L R ...).
 * Lets us process songs of any length with a few MB of RAM.
 */
public final class FloatTrack implements Closeable {
    public final File file;
    private final RandomAccessFile raf;
    private final FileChannel ch;
    private long frames;      // stereo sample frames
    private float peak = 0f;

    private FloatTrack(File f, boolean write) throws IOException {
        file = f;
        raf = new RandomAccessFile(f, write ? "rw" : "r");
        ch = raf.getChannel();
        frames = raf.length() / 8;
    }

    public static FloatTrack create(File f) throws IOException {
        if (f.exists() && !f.delete()) throw new IOException("cannot replace " + f);
        return new FloatTrack(f, true);
    }

    public static FloatTrack open(File f) throws IOException { return new FloatTrack(f, false); }

    public long frames() { return frames; }
    public float peak() { return peak; }

    /** Append interleaved stereo floats. */
    public void append(float[] interleaved, int count) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(interleaved, 0, count);
        for (int i = 0; i < count; i++) { float a = Math.abs(interleaved[i]); if (a > peak) peak = a; }
        bb.limit(count * 4);
        while (bb.hasRemaining()) ch.write(bb);
        frames += count / 2;
    }

    /** Append [2][n] planar block. */
    public void append(float[][] planar, int n) throws IOException {
        float[] il = new float[n * 2];
        for (int i = 0; i < n; i++) { il[2 * i] = planar[0][i]; il[2 * i + 1] = planar[1][i]; }
        append(il, n * 2);
    }

    /**
     * Read `count` frames starting at `start` into dst[2][count]. Positions outside
     * [0, frames) read as zero (this is how all padding in the pipeline is realised).
     */
    public void read(long start, int count, float[][] dst) throws IOException {
        java.util.Arrays.fill(dst[0], 0, count, 0f);
        java.util.Arrays.fill(dst[1], 0, count, 0f);
        long from = Math.max(0, start);
        long to = Math.min(frames, start + count);
        if (to <= from) return;
        int n = (int) (to - from);
        ByteBuffer bb = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN);
        ch.position(from * 8);
        while (bb.hasRemaining()) if (ch.read(bb) < 0) break;
        bb.flip();
        FloatBuffer fb = bb.asFloatBuffer();
        int o = (int) (from - start);
        for (int i = 0; i < n; i++) { dst[0][o + i] = fb.get(); dst[1][o + i] = fb.get(); }
    }

    public void computePeak() throws IOException {
        peak = 0f;
        float[][] buf = new float[2][1 << 16];
        for (long p = 0; p < frames; p += 1 << 16) {
            int n = (int) Math.min(1 << 16, frames - p);
            read(p, n, buf);
            for (int i = 0; i < n; i++) {
                float a = Math.abs(buf[0][i]); if (a > peak) peak = a;
                a = Math.abs(buf[1][i]); if (a > peak) peak = a;
            }
        }
    }

    @Override public void close() throws IOException { ch.close(); raf.close(); }
}
