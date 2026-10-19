package com.tunetech.drumsep;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private TextView modelStatus, songName, stepText, etaText, resultTitle;
    private Button btnDownload, btnPick, btnStart, btnCancel;
    private ProgressBar bar;
    private View progressCard, resultCard;
    private LinearLayout stemList;
    private RadioButton qBest, sCool, sMax, eXnn, eNpu;
    private CheckBox drumsOnly;

    private Uri songUri;
    private String songDisplay;
    private MediaPlayer player;
    private Button playingBtn;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int renderedStems = -1;
    private JobState.Phase renderedPhase = null;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) setSong(uri); });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        modelStatus = findViewById(R.id.modelStatus); songName = findViewById(R.id.songName);
        stepText = findViewById(R.id.stepText); etaText = findViewById(R.id.etaText); resultTitle = findViewById(R.id.resultTitle);
        btnDownload = findViewById(R.id.btnDownload); btnPick = findViewById(R.id.btnPick);
        btnStart = findViewById(R.id.btnStart); btnCancel = findViewById(R.id.btnCancel);
        bar = findViewById(R.id.bar); progressCard = findViewById(R.id.progressCard); resultCard = findViewById(R.id.resultCard);
        stemList = findViewById(R.id.stemList); qBest = findViewById(R.id.qBest); sCool = findViewById(R.id.sCool); sMax = findViewById(R.id.sMax); eXnn = findViewById(R.id.eXnn); eNpu = findViewById(R.id.eNpu);
        restoreSettings(); drumsOnly = findViewById(R.id.drumsOnly);

        btnPick.setOnClickListener(v -> picker.launch(new String[]{"audio/*"}));
        btnDownload.setOnClickListener(v -> startJob(true));
        btnStart.setOnClickListener(v -> startJob(false));
        btnCancel.setOnClickListener(v -> startService(new Intent(this, SeparationService.class).setAction(SeparationService.ACTION_CANCEL)));

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 11);

        handleIncoming(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleIncoming(i); }

    private void handleIncoming(Intent i) {
        if (i == null) return;
        Uri u = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        else if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        if (u != null) setSong(u);
    }

    private void setSong(Uri uri) {
        songUri = uri;
        songDisplay = queryName(uri);
        songName.setText(songDisplay);
        refresh();
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null) return n; }
        } catch (Exception ignored) {}
        String p = uri.getLastPathSegment();
        return p == null ? "song" : p;
    }

    private void startJob(boolean downloadOnly) {
        JobState st = JobState.get();
        if (st.isBusy()) return;
        Intent i = new Intent(this, SeparationService.class);
        if (downloadOnly) {
            // Reuse the service just for downloading: a job with no song URI stops after the model step.
            new Thread(() -> {
                st.reset("models"); st.phase = JobState.Phase.DOWNLOADING;
                try {
                    for (ModelManager.Model m : ModelManager.ALL)
                        ModelManager.download(this, m, (mm, done, total) -> {
                            st.percent = (int) (done * 100 / total);
                            st.detail = "Downloading " + mm.title + "  " + (done >> 20) + " / " + (total >> 20) + " MB";
                            return !st.cancelRequested;
                        });
                    st.phase = JobState.Phase.IDLE; st.detail = "";
                } catch (Exception e) {
                    st.phase = JobState.Phase.ERROR; st.error = "Download failed: " + e.getMessage(); st.detail = st.error;
                }
            }, "model-download").start();
            return;
        }
        if (songUri == null) return;
        try { getContentResolver().takePersistableUriPermission(songUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        i.putExtra(SeparationService.EXTRA_URI, songUri);
        i.putExtra(SeparationService.EXTRA_NAME, songDisplay);
        i.putExtra(SeparationService.EXTRA_OVERLAP, qBest.isChecked() ? 4 : 2);
        i.putExtra(SeparationService.EXTRA_DRUMS_ONLY, drumsOnly.isChecked());
        i.putExtra(SeparationService.EXTRA_SPEED, sCool.isChecked() ? 0 : sMax.isChecked() ? 2 : 1);
        i.putExtra(SeparationService.EXTRA_ENGINE, eXnn.isChecked() ? 1 : eNpu.isChecked() ? 2 : 0);
        saveSettings();
        stopPlayback();
        ContextCompat.startForegroundService(this, i);
        renderedStems = -1;
    }

    private void saveSettings() {
        getSharedPreferences("drumsep", MODE_PRIVATE).edit()
                .putBoolean("best", qBest.isChecked())
                .putInt("speed", sCool.isChecked() ? 0 : sMax.isChecked() ? 2 : 1)
                .putInt("engine", eXnn.isChecked() ? 1 : eNpu.isChecked() ? 2 : 0).apply();
    }

    private void restoreSettings() {
        android.content.SharedPreferences p = getSharedPreferences("drumsep", MODE_PRIVATE);
        ((RadioButton) findViewById(p.getBoolean("best", false) ? R.id.qBest : R.id.qFast)).setChecked(true);
        int sp = p.getInt("speed", 0);
        ((RadioButton) findViewById(sp == 0 ? R.id.sCool : sp == 2 ? R.id.sMax : R.id.sNormal)).setChecked(true);
        int en = p.getInt("engine", 0);
        ((RadioButton) findViewById(en == 1 ? R.id.eXnn : en == 2 ? R.id.eNpu : R.id.eCpu)).setChecked(true);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 500); }
    };

    @Override protected void onResume() { super.onResume(); h.post(ticker); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(ticker); }
    @Override protected void onDestroy() { super.onDestroy(); stopPlayback(); }

    private void refresh() {
        JobState st = JobState.get();
        boolean ready = ModelManager.allReady(this);
        boolean busy = st.isBusy();
        long have = 0, total = 0;
        for (ModelManager.Model m : ModelManager.ALL) { total += m.bytes; if (ModelManager.isReady(this, m)) have += m.bytes; }
        modelStatus.setText(ready ? "✓ Tools installed" : (have >> 20) + " / " + (total >> 20) + " MB installed");
        btnDownload.setVisibility(ready ? View.GONE : View.VISIBLE);
        btnDownload.setEnabled(!busy);
        btnPick.setEnabled(!busy);
        btnStart.setEnabled(!busy && songUri != null);
        btnStart.setText(ready ? "Separate drum parts" : "Download tools + separate");

        boolean showProgress = busy || st.phase == JobState.Phase.ERROR || st.phase == JobState.Phase.CANCELLED;
        progressCard.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        if (showProgress) {
            bar.setIndeterminate(busy && st.percent == 0 && st.phase != JobState.Phase.DOWNLOADING);
            bar.setProgress(st.percent);
            String t = st.detail;
            if (st.phase == JobState.Phase.ERROR) t = "✗ " + st.error;
            stepText.setText(t);
            stepText.setTextColor(getColor(st.phase == JobState.Phase.ERROR ? R.color.err : R.color.muted));
            etaText.setText(busy && st.etaSeconds >= 0 ? "about " + fmt(st.etaSeconds) + " left" : "");
            btnCancel.setVisibility(busy ? View.VISIBLE : View.GONE);
        }

        if (st.phase == JobState.Phase.DONE) {
            resultCard.setVisibility(View.VISIBLE);
            resultTitle.setText("✓ " + st.songName + "\nSaved in " + st.outputFolder);
            List<JobState.Stem> stems;
            synchronized (st.stems) { stems = new ArrayList<>(st.stems); }
            if (stems.size() != renderedStems || renderedPhase != st.phase) { renderStems(stems); renderedStems = stems.size(); }
        } else if (st.phase != renderedPhase) {
            resultCard.setVisibility(View.GONE);
        }
        renderedPhase = st.phase;
    }

    private static String fmt(long s) {
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min " + (s % 60) + " s";
        return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
    }

    private void renderStems(List<JobState.Stem> stems) {
        stemList.removeAllViews();
        for (JobState.Stem s : stems) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 10, 0, 10);
            TextView name = new TextView(this);
            name.setText(s.label);
            name.setTextColor(getColor(R.color.text));
            name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Button play = new Button(this);
            play.setText("▶");
            play.setOnClickListener(v -> togglePlay(s.uri, play));
            Button share = new Button(this);
            share.setText("Share");
            share.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, s.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Share " + s.key + ".wav"));
            });
            row.addView(name); row.addView(play); row.addView(share);
            stemList.addView(row);
        }
    }

    private void togglePlay(Uri uri, Button btn) {
        if (playingBtn == btn) { stopPlayback(); return; }
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(this, uri);
            player.setOnCompletionListener(mp -> stopPlayback());
            player.prepare();
            player.start();
            playingBtn = btn;
            btn.setText("■");
        } catch (Exception e) {
            Toast.makeText(this, "Cannot play: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            stopPlayback();
        }
    }

    private void stopPlayback() {
        if (player != null) { try { player.stop(); } catch (Exception ignored) {} player.release(); player = null; }
        if (playingBtn != null) { playingBtn.setText("▶"); playingBtn = null; }
    }
}
