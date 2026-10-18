package com.tunetech.drumsep;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import androidx.core.app.NotificationCompat;
import com.tunetech.drumsep.dsp.FloatTrack;
import com.tunetech.drumsep.dsp.Separator;
import com.tunetech.drumsep.dsp.WavWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Foreground service: download models (if needed) → decode → stage 1 → stage 2 → save WAVs. */
public final class SeparationService extends Service {
    public static final String EXTRA_URI = "uri", EXTRA_NAME = "name", EXTRA_OVERLAP = "overlap", EXTRA_DRUMS_ONLY = "drumsOnly";
    public static final String ACTION_CANCEL = "com.tunetech.drumsep.CANCEL";
    private static final String CH = "drumsep_progress";
    private static final int NOTIF = 1;

    private static final String[] LABELS = {"Kick drum", "Snare (rim inside)", "Tom-toms", "Hi-hat", "Cymbal (ride)", "Crash"};

    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        JobState st = JobState.get();
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            st.cancelRequested = true;
            if (worker != null) worker.interrupt();
            return START_NOT_STICKY;
        }
        if (intent == null || st.isBusy()) return START_NOT_STICKY;
        Uri uri = intent.getParcelableExtra(EXTRA_URI);
        String name = intent.getStringExtra(EXTRA_NAME);
        int overlap = intent.getIntExtra(EXTRA_OVERLAP, 2);
        boolean drumsOnly = intent.getBooleanExtra(EXTRA_DRUMS_ONLY, false);
        st.reset(name);
        st.phase = JobState.Phase.DOWNLOADING;
        startForegroundCompat("Starting…");
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "drumsep:work");
        wakeLock.acquire(3 * 60 * 60 * 1000L);
        worker = new Thread(() -> run(uri, name, overlap, drumsOnly), "drumsep-worker");
        worker.start();
        return START_NOT_STICKY;
    }

    private void run(Uri uri, String name, int overlap, boolean drumsOnly) {
        JobState st = JobState.get();
        File cache = new File(getCacheDir(), "work");
        cache.mkdirs();
        File mixF = new File(cache, "mix.f32"), drumsF = new File(cache, "drums.f32");
        try {
            // 1. models
            for (ModelManager.Model m : ModelManager.ALL) {
                if (drumsOnly && m == ModelManager.DRUMS) continue;
                st.phase = JobState.Phase.DOWNLOADING;
                final long[] t0 = {System.currentTimeMillis()};
                ModelManager.download(this, m, (mm, done, total) -> {
                    st.percent = (int) (done * 100 / total);
                    st.detail = "Downloading " + mm.title + "  " + (done >> 20) + " / " + (total >> 20) + " MB";
                    long el = System.currentTimeMillis() - t0[0];
                    st.etaSeconds = done > 0 ? (long) ((total - done) * (el / 1000.0) / done) : -1;
                    notify(st.detail, st.percent);
                    return !st.cancelRequested;
                });
            }
            checkCancel();

            // 2. decode
            st.phase = JobState.Phase.DECODING; st.percent = 0; st.etaSeconds = -1;
            st.detail = "Reading audio…";
            notify(st.detail, 0);
            FloatTrack mix = AudioDecoder.decode(this, uri, mixF, f -> {
                st.percent = (int) (f * 100); return !st.cancelRequested;
            });
            long frames = mix.frames();
            int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));

            // 3. stage 1
            FloatTrack drums;
            if (drumsOnly) {
                drums = mix;
            } else {
                st.phase = JobState.Phase.STAGE1; st.percent = 0;
                st.detail = "Stage 1 / 2 · separating drums from the song";
                notify(st.detail, 0);
                FloatTrack drumsOut = FloatTrack.create(drumsF);
                long t1 = System.currentTimeMillis();
                try (OnnxRunner m1 = new OnnxRunner(ModelManager.file(this, ModelManager.DRUMS).getPath(),
                        new long[]{1, 4, Separator.S1_DIMF, Separator.S1_DIMT}, threads)) {
                    Separator.stage1(mix, drumsOut, m1, (d, t) -> progress(st, "Stage 1 / 2 · drums from song", d, t, t1));
                }
                drumsOut.close();
                mix.close();
                drums = FloatTrack.open(drumsF);
            }
            checkCancel();

            // 4. stage 2 → WAV files
            st.phase = JobState.Phase.STAGE2; st.percent = 0;
            st.detail = "Stage 2 / 2 · splitting the drum kit";
            notify(st.detail, 0);
            String folder = safeName(name);
            Output[] outs = new Output[6];
            for (int k = 0; k < 6; k++) outs[k] = openOutput(folder, Separator.STEMS[k] + ".wav", frames);
            long t2 = System.currentTimeMillis();
            try (OnnxRunner m2 = new OnnxRunner(ModelManager.file(this, ModelManager.DRUMSEP).getPath(),
                    new long[]{1, 4, Separator.S2_DIMF, Separator.S2_DIMT}, threads)) {
                Separator.stage2(drums, overlap, m2, (k, pl, off, n) -> outs[k].w.write(pl, off, n),
                        (d, t) -> progress(st, "Stage 2 / 2 · splitting the drum kit", d, t, t2));
            } finally {
                for (Output o : outs) if (o != null) try { o.w.close(); } catch (IOException ignored) {}
            }
            // full drum kit as a bonus file
            st.phase = JobState.Phase.SAVING; st.detail = "Saving files…";
            Output full = openOutput(folder, "drums_full.wav", frames);
            float[][] buf = new float[2][1 << 15];
            for (long p = 0; p < frames; p += 1 << 15) {
                int n = (int) Math.min(1 << 15, frames - p);
                drums.read(p, n, buf); full.w.write(buf, 0, n);
            }
            full.w.close();
            drums.close();
            for (int k = 0; k < 6; k++) outs[k].finish(this);
            full.finish(this);

            synchronized (st.stems) {
                for (int k = 0; k < 6; k++) st.stems.add(new JobState.Stem(Separator.STEMS[k], LABELS[k], outs[k].uri));
                st.stems.add(new JobState.Stem("drums_full", "Full drum kit", full.uri));
            }
            st.outputFolder = "Music/DrumSep/" + folder;
            st.phase = JobState.Phase.DONE; st.percent = 100; st.detail = "Done";
            notifyFinal("Drum parts ready", "Saved in " + st.outputFolder);
        } catch (Throwable e) {
            boolean cancelled = st.cancelRequested || e instanceof InterruptedException || "cancelled".equals(e.getMessage());
            st.phase = cancelled ? JobState.Phase.CANCELLED : JobState.Phase.ERROR;
            st.error = cancelled ? "Cancelled" : describe(e);
            st.detail = st.error;
            if (!cancelled) notifyFinal("DrumSep failed", st.error);
            else ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF);
        } finally {
            mixF.delete(); drumsF.delete();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private boolean progress(JobState st, String what, int d, int t, long t0) {
        st.percent = d * 100 / t;
        long el = System.currentTimeMillis() - t0;
        st.etaSeconds = d > 0 ? (long) ((t - d) * (el / 1000.0) / d) : -1;
        st.detail = what + "  (" + d + " / " + t + ")";
        if (d % 3 == 0 || d == t) notify(what + " " + st.percent + "%", st.percent);
        return !st.cancelRequested;
    }

    private void checkCancel() throws InterruptedException {
        if (JobState.get().cancelRequested) throw new InterruptedException("cancelled");
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (e instanceof OutOfMemoryError) return "Not enough memory. Close other apps and try again.";
        return (e.getClass().getSimpleName() + (m != null ? ": " + m : ""));
    }

    static String safeName(String name) {
        String n = name == null ? "song" : name;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceAll("[^\\p{L}\\p{N} _\\-]", "_").trim();
        if (n.isEmpty()) n = "song";
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    // ---------------------------------------------------------------- output files
    private static final class Output {
        WavWriter w; Uri uri; File legacyFile;
        void finish(Context c) {
            if (uri != null && Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Audio.Media.IS_PENDING, 0);
                c.getContentResolver().update(uri, cv, null, null);
            }
        }
    }

    private Output openOutput(String folder, String fileName, long frames) throws IOException {
        Output o = new Output();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            String rel = Environment.DIRECTORY_MUSIC + "/DrumSep/" + folder;
            // replace an older file with the same name
            try (android.database.Cursor cur = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Audio.Media._ID},
                    MediaStore.Audio.Media.RELATIVE_PATH + "=? AND " + MediaStore.Audio.Media.DISPLAY_NAME + "=?",
                    new String[]{rel + "/", fileName}, null)) {
                while (cur != null && cur.moveToNext())
                    cr.delete(Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(cur.getLong(0))), null, null);
            } catch (Exception ignored) {}
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
            cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
            cv.put(MediaStore.Audio.Media.RELATIVE_PATH, rel);
            cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
            cv.put(MediaStore.Audio.Media.IS_MUSIC, 1);
            o.uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
            if (o.uri == null) throw new IOException("Cannot create " + fileName + " in Music/DrumSep");
            OutputStream os = cr.openOutputStream(o.uri);
            if (os == null) throw new IOException("Cannot open " + fileName);
            o.w = new WavWriter(os, AudioDecoder.SR, frames);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "DrumSep/" + folder);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create folder " + dir);
            o.legacyFile = new File(dir, fileName);
            o.w = new WavWriter(new FileOutputStream(o.legacyFile), AudioDecoder.SR, frames);
            o.uri = Uri.fromFile(o.legacyFile);
        }
        return o;
    }

    // ---------------------------------------------------------------- notifications
    private void startForegroundCompat(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(new NotificationChannel(CH, "Separation progress", NotificationManager.IMPORTANCE_LOW));
        Notification n = build(text, 0, true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIF, n);
    }

    private Notification build(String text, int pct, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent cancel = new Intent(this, SeparationService.class).setAction(ACTION_CANCEL);
        PendingIntent cpi = PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_notify).setContentTitle("DrumSep · " + JobState.get().songName)
                .setContentText(text).setContentIntent(pi).setOnlyAlertOnce(true).setOngoing(ongoing)
                .setProgress(100, pct, pct == 0);
        if (ongoing) b.addAction(0, "Cancel", cpi);
        return b.build();
    }

    private long lastNotify = 0;
    private void notify(String text, int pct) {
        long now = System.currentTimeMillis();
        if (now - lastNotify < 1000 && pct != 100) return;
        lastNotify = now;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF, build(text, pct, true));
    }

    private void notifyFinal(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true).build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF + 1, n);
    }
}
