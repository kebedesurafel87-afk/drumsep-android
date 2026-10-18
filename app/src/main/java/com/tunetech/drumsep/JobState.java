package com.tunetech.drumsep;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** Shared, observable state of the current job (service writes, activity reads). */
public final class JobState {
    public enum Phase { IDLE, DOWNLOADING, DECODING, STAGE1, STAGE2, SAVING, DONE, ERROR, CANCELLED }

    public static final class Stem {
        public final String key, label; public final Uri uri;
        Stem(String key, String label, Uri uri) { this.key = key; this.label = label; this.uri = uri; }
    }

    private static final JobState I = new JobState();
    public static JobState get() { return I; }

    public volatile Phase phase = Phase.IDLE;
    public volatile String detail = "";
    public volatile int percent = 0;          // 0..100 for the current phase
    public volatile long etaSeconds = -1;
    public volatile String error = "";
    public volatile String songName = "";
    public volatile String outputFolder = "";
    public volatile boolean cancelRequested = false;
    public final List<Stem> stems = new ArrayList<>();

    public boolean isBusy() {
        return phase == Phase.DOWNLOADING || phase == Phase.DECODING || phase == Phase.STAGE1
                || phase == Phase.STAGE2 || phase == Phase.SAVING;
    }

    void reset(String song) {
        phase = Phase.IDLE; detail = ""; percent = 0; etaSeconds = -1; error = ""; songName = song;
        outputFolder = ""; cancelRequested = false;
        synchronized (stems) { stems.clear(); }
    }
}
