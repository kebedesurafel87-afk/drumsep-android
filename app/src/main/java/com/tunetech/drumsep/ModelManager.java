package com.tunetech.drumsep;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads the two neural-network files once and keeps them in the app's private storage. */
public final class ModelManager {

    /** Change this if you host the models somewhere else. */
    public static final String MODELS_BASE = BuildConfig.MODELS_BASE;

    public static final class Model {
        public final String file; public final String url; public final long bytes; public final String title;
        Model(String file, String url, long bytes, String title) { this.file = file; this.url = url; this.bytes = bytes; this.title = title; }
    }

    public static final Model DRUMS = new Model("kuielab_b_drums.onnx",
            "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/kuielab_b_drums.onnx",
            21930313L, "Model 1 · drums from song (21 MB)");
    public static final Model DRUMSEP = new Model("drumsep_mdx23c.onnx",
            MODELS_BASE + "/drumsep_mdx23c.onnx",
            437703505L, "Model 2 · drum parts (418 MB)");
    public static final Model[] ALL = {DRUMS, DRUMSEP};

    public interface Listener { boolean onProgress(Model m, long done, long total); }

    public static File dir(Context c) { File d = new File(c.getFilesDir(), "models"); d.mkdirs(); return d; }
    public static File file(Context c, Model m) { return new File(dir(c), m.file); }
    public static boolean isReady(Context c, Model m) { File f = file(c, m); return f.exists() && f.length() == m.bytes; }
    public static boolean allReady(Context c) { for (Model m : ALL) if (!isReady(c, m)) return false; return true; }

    /** Download (with resume) a model. Throws on failure. */
    public static void download(Context c, Model m, Listener l) throws IOException {
        if (isReady(c, m)) return;
        File dst = file(c, m);
        File part = new File(dst.getPath() + ".part");
        if (dst.exists()) dst.delete();
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                long have = part.exists() ? part.length() : 0;
                if (have > m.bytes) { part.delete(); have = 0; }
                if (have < m.bytes) {
                    HttpURLConnection conn = (HttpURLConnection) new URL(m.url).openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(60000);
                    if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
                    int code = conn.getResponseCode();
                    if (code == 200 && have > 0) { part.delete(); have = 0; }
                    else if (code != 200 && code != 206) throw new IOException("HTTP " + code + " downloading " + m.file);
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(part, have > 0)) {
                        byte[] buf = new byte[1 << 16];
                        int n; long lastCb = 0;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            have += n;
                            if (l != null && (have - lastCb > (1 << 20) || have == m.bytes)) {
                                lastCb = have;
                                if (!l.onProgress(m, have, m.bytes)) throw new IOException("cancelled");
                            }
                        }
                    }
                }
                if (part.length() != m.bytes) throw new IOException("Incomplete download of " + m.file + " (" + part.length() + "/" + m.bytes + ")");
                if (!part.renameTo(dst)) throw new IOException("Cannot rename " + part);
                return;
            } catch (IOException e) {
                last = e;
                if ("cancelled".equals(e.getMessage())) throw e;
                try { Thread.sleep(1500); } catch (InterruptedException ie) { throw new IOException("cancelled"); }
            }
        }
        throw last;
    }
}
