package com.tunetech.drumsep;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import com.tunetech.drumsep.dsp.FloatTrack;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes any audio the phone can play (mp3, m4a, wav, flac, ogg…) to 44.1 kHz stereo float32. */
public final class AudioDecoder {
    public static final int SR = 44100;

    public interface Listener { boolean onProgress(float fraction); }

    public static FloatTrack decode(Context ctx, Uri uri, File out, Listener l) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, uri, null);
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
        }
        if (track < 0) throw new IOException("No audio track in file");
        ex.selectTrack(track);
        long durationUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;

        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();

        FloatTrack ft = FloatTrack.create(out);
        int srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int pcmEnc = 2; // 16-bit
        Resampler rs = new Resampler(srcRate);

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inDone = false, outDone = false;
        float[] stereo = new float[0];
        try {
            while (!outDone) {
                if (!inDone) {
                    int ib = codec.dequeueInputBuffer(10_000);
                    if (ib >= 0) {
                        ByteBuffer b = codec.getInputBuffer(ib);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ib, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int ob = codec.dequeueOutputBuffer(info, 10_000);
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    srcCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEnc = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    rs = new Resampler(srcRate);
                } else if (ob >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    ByteBuffer b = codec.getOutputBuffer(ob);
                    if (b != null && info.size > 0) {
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        ByteBuffer le = b.slice().order(ByteOrder.LITTLE_ENDIAN);
                        int frames;
                        if (pcmEnc == 4) { // float
                            frames = info.size / 4 / srcCh;
                            if (stereo.length < frames * 2) stereo = new float[frames * 2];
                            for (int i = 0; i < frames; i++) {
                                float L = le.getFloat(), R = L;
                                for (int c = 1; c < srcCh; c++) { float v = le.getFloat(); if (c == 1) R = v; }
                                stereo[2 * i] = L; stereo[2 * i + 1] = R;
                            }
                        } else {
                            frames = info.size / 2 / srcCh;
                            if (stereo.length < frames * 2) stereo = new float[frames * 2];
                            for (int i = 0; i < frames; i++) {
                                float L = le.getShort() / 32768f, R = L;
                                for (int c = 1; c < srcCh; c++) { float v = le.getShort() / 32768f; if (c == 1) R = v; }
                                stereo[2 * i] = L; stereo[2 * i + 1] = R;
                            }
                        }
                        rs.push(stereo, frames, ft);
                    }
                    codec.releaseOutputBuffer(ob, false);
                    if (l != null && durationUs > 0 && !l.onProgress(Math.min(1f, info.presentationTimeUs / (float) durationUs)))
                        throw new IOException("cancelled");
                }
            }
            rs.flush(ft);
        } finally {
            try { codec.stop(); } catch (Exception ignored) {}
            codec.release();
            ex.release();
        }
        if (ft.frames() < SR) { ft.close(); throw new IOException("Audio is too short or could not be decoded"); }
        return ft;
    }

    /** Linear-interpolation resampler to 44.1 kHz (identity when already 44.1 kHz). */
    static final class Resampler {
        final int srcRate;
        final double ratio;        // src samples per output sample
        double pos = 0;            // position in source stream (absolute, fractional)
        long consumed = 0;         // source frames already dropped
        float lastL = 0, lastR = 0;
        boolean hasLast = false;
        float[] outBuf = new float[1 << 16];

        Resampler(int srcRate) { this.srcRate = srcRate; ratio = srcRate / (double) SR; }

        void push(float[] il, int frames, FloatTrack ft) throws IOException {
            if (srcRate == SR) { ft.append(il, frames * 2); return; }
            // build a source window = [last frame] + this block, so interpolation crosses block edges
            int base = hasLast ? 1 : 0;
            float[] src = new float[(frames + base) * 2];
            if (hasLast) { src[0] = lastL; src[1] = lastR; }
            System.arraycopy(il, 0, src, base * 2, frames * 2);
            long windowStart = consumed - base;       // absolute index of src[0]
            int n = 0;
            while (true) {
                double rel = pos - windowStart;
                int i0 = (int) Math.floor(rel);
                if (i0 + 1 >= frames + base) break;
                float fr = (float) (rel - i0);
                float L = src[2 * i0] + (src[2 * i0 + 2] - src[2 * i0]) * fr;
                float R = src[2 * i0 + 1] + (src[2 * i0 + 3] - src[2 * i0 + 1]) * fr;
                if (n + 2 > outBuf.length) { ft.append(outBuf, n); n = 0; }
                outBuf[n++] = L; outBuf[n++] = R;
                pos += ratio;
            }
            if (n > 0) ft.append(outBuf, n);
            lastL = il[2 * frames - 2]; lastR = il[2 * frames - 1]; hasLast = true;
            consumed += frames;
        }

        void flush(FloatTrack ft) {}
    }
}
