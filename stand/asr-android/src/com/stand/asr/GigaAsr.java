/*
 * Russian Voice Assistant modification for Changan A06 (C390).
 * Copyright (c) 2026 Tecrow.
 * Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only. See LICENSE.
 * Independent modification — not affiliated with or endorsed by Changan Automobile.
 */
package com.stand.asr;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * GigaAM-v3 CTC offline ASR backend. Runs on sherpa-onnx (the same native
 * libsherpa-onnx-jni.so we already bundle for Piper — it carries GigaAM `is_giga_am` fbank support).
 *
 * GigaAM is a full-utterance Conformer, NOT streaming: audio (16 kHz mono s16le) arrives as chunks
 * from the stock SR callback with a phase flag (wp==1 start .. wp==3 end). We accumulate the whole
 * utterance between wp==1 and wp==3, then run one greedy-CTC decode. sherpa computes the 64-bin
 * GigaAM log-mel (via the model's `is_giga_am` metadata) and the CTC decode internally, so no
 * hand-rolled feature extraction. Model assets (~224 MB int8) unpack from apk assets to filesDir once.
 */
public final class GigaAsr {
    private static final String TAG = "GigaAsr";
    private static final String ASSET_DIR = "gigaam";   // apk assets/gigaam: model.int8.onnx, tokens.txt
    private static final String MODEL = "model.int8.onnx";
    private static final String TOKENS = "tokens.txt";
    private static final int FEAT_DIM = 64;             // GigaAM v3 = 64 mel bins (NOT 80)
    private static final int RATE = 16000;
    private static final int THREADS = 3;               // big cluster on MT6897 (like TeraTTS)

    private static volatile OfflineRecognizer rec;
    /** true when the bundled tokens.txt is the GigaAM-Multilingual vocabulary (Latin + Cyrillic +
     *  Kazakh/Kyrgyz letters) — the "multi" build (build_sa GIGAAM_ML=1). false = v3 Russian-only. */
    private static volatile boolean multilingual;
    private static Context appCtx;
    private static final ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 20);

    public static void init(final Context c) {
        appCtx = c.getApplicationContext();
        if (rec != null) return;
        new Thread(new Runnable() { public void run() { ensure(); } }).start();
    }

    private static synchronized boolean ensure() {
        if (rec != null) return true;
        try {
            if (appCtx == null) return false;
            File dir = new File(appCtx.getFilesDir(), ASSET_DIR);
            File onnx = new File(dir, MODEL), toks = new File(dir, TOKENS);
            if (needsUnpack(dir, onnx, toks)) {
                Log.i(TAG, "unpacking GigaAM assets to " + dir + " (~224 MB)");
                deleteRec(dir);
                unpackAssets(ASSET_DIR, dir);
                writeText(new File(dir, SIG), assetsSig());
            }
            multilingual = vocabHasLatin(toks);
            Log.i(TAG, "vocabulary: " + (multilingual ? "multilingual (ru/kk/ky/uz/en)" : "russian (v3)"));
            OfflineNemoEncDecCtcModelConfig nemo =
                    OfflineNemoEncDecCtcModelConfig.builder().setModel(onnx.getAbsolutePath()).build();
            OfflineModelConfig model = OfflineModelConfig.builder()
                    .setNemo(nemo)
                    .setTokens(toks.getAbsolutePath())
                    .setNumThreads(THREADS)
                    .setDebug(false)
                    .setProvider("cpu")
                    .build();
            FeatureConfig feat = FeatureConfig.builder().setSampleRate(RATE).setFeatureDim(FEAT_DIM).build();
            OfflineRecognizerConfig cfg = OfflineRecognizerConfig.builder()
                    .setFeatureConfig(feat)
                    .setOfflineModelConfig(model)
                    .setDecodingMethod("greedy_search")
                    .build();
            long t0 = System.currentTimeMillis();
            rec = new OfflineRecognizer(cfg);
            Log.i(TAG, "recognizer ready in " + (System.currentTimeMillis() - t0) + "ms");
            return true;
        } catch (Throwable t) { Log.e(TAG, "ensure", t); return false; }
    }

    public static boolean ready() { return rec != null; }

    // 350 ms ring buffer of pre-roll audio: 16000 samples/sec * 2 bytes/sample * 0.35s = 11200 bytes
    private static final int PREROLL_SIZE = 11200;
    private static final byte[] preroll = new byte[PREROLL_SIZE];
    private static int prerollHead = 0;
    private static int prerollCount = 0;

    /** Multilingual build (GigaAM-Multilingual CTC)? Only meaningful once the recognizer is ready. */
    public static boolean multilingual() { return multilingual; }

    // `pm install -r` keeps filesDir. A plain "files exist" check therefore left the PREVIOUS build's model in
    // place: the multilingual APK installed over the Russian one kept running GigaAM-v3 with the v3 tokens
    // (and reported itself as Russian). Same trap TeraTts had. The unpacked set is now tied to the bundled one
    // by a signature — name:size of each asset (cheap: openFd length, the assets are zip-stored).
    private static final String SIG = ".sig";

    private static boolean needsUnpack(File dir, File onnx, File toks) {
        if (!onnx.exists() || !toks.exists()) return true;
        String want = assetsSig();
        if (want.isEmpty()) return false;                       // asset sizes unavailable → trust what is on disk
        String have = readText(new File(dir, SIG));
        if (want.equals(have)) return false;
        Log.i(TAG, "model signature changed: apk=" + want + " disk=" + have);
        return true;
    }

    private static String assetsSig() {
        long m = assetLen(ASSET_DIR + "/" + MODEL), t = assetLen(ASSET_DIR + "/" + TOKENS);
        return (m < 0 || t < 0) ? "" : MODEL + ":" + m + "|" + TOKENS + ":" + t;
    }

    private static long assetLen(String path) {
        try { android.content.res.AssetFileDescriptor fd = appCtx.getAssets().openFd(path);
              long n = fd.getLength(); fd.close(); return n; }
        catch (Throwable t) { return -1; }
    }

    private static void deleteRec(File f) {
        try { if (f.isDirectory()) { File[] ch = f.listFiles(); if (ch != null) for (File c : ch) deleteRec(c); }
              f.delete(); } catch (Throwable ignored) {}
    }

    private static String readText(File f) {
        try { byte[] b = new byte[(int) f.length()]; java.io.FileInputStream in = new java.io.FileInputStream(f);
              int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n; in.close();
              return new String(b, 0, off, "UTF-8"); }
        catch (Throwable t) { return ""; }
    }

    private static void writeText(File f, String s) {
        try { FileOutputStream o = new FileOutputStream(f); o.write(s.getBytes("UTF-8")); o.close(); }
        catch (Throwable t) { Log.e(TAG, "writeText", t); }
    }

    /** tokens.txt lines are "<char> <id>"; the multilingual vocabulary has a-z, the v3 one doesn't. */
    private static boolean vocabHasLatin(File tokens) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(tokens), "UTF-8"));
            String line; boolean latin = false;
            while ((line = r.readLine()) != null) {
                if (line.length() >= 3 && line.charAt(1) == ' ' && line.charAt(0) >= 'a' && line.charAt(0) <= 'z') { latin = true; break; }
            }
            r.close();
            return latin;
        } catch (Throwable t) { Log.e(TAG, "vocabHasLatin", t); return false; }
    }

    /** Language of a transcript ("ru" | "kk" | "ky" | "uz" | "en") — see {@link LangDetect}. */
    public static String detectLang(String text) { return LangDetect.detect(text); }

    /** Start of a new utterance (wp==1): preserve pre-roll audio so initial consonants are never clipped. */
    public static void reset() {
        synchronized (buf) {
            buf.reset();
            if (prerollCount > 0) {
                if (prerollCount < PREROLL_SIZE) {
                    buf.write(preroll, 0, prerollCount);
                } else {
                    buf.write(preroll, prerollHead, PREROLL_SIZE - prerollHead);
                    if (prerollHead > 0) {
                        buf.write(preroll, 0, prerollHead);
                    }
                }
            }
        }
    }

    /** Middle chunks: append raw 16-bit LE PCM and keep ring-buffer. */
    public static void accept(byte[] pcm, int len) {
        if (com.stand.bridge.RuBridge.isTtsSpeaking()) return;
        if (pcm == null || len <= 0) return;
        int act = Math.min(len, pcm.length);
        synchronized (buf) {
            buf.write(pcm, 0, act);
            for (int i = 0; i < act; i++) {
                preroll[prerollHead] = pcm[i];
                prerollHead = (prerollHead + 1) % PREROLL_SIZE;
                if (prerollCount < PREROLL_SIZE) prerollCount++;
            }
        }
    }

    /** End of utterance (wp==3): decode accumulated PCM to text (lowercase, no punctuation). */
    public static String finish() {
        if (!ensure()) { Log.e(TAG, "finish: recognizer not ready"); return ""; }
        byte[] b;
        synchronized (buf) {
            b = buf.toByteArray();
            buf.reset();
            prerollCount = 0;
            prerollHead = 0;
        }
        if (b.length < RATE / 25 * 2) return "";   // <40 ms of audio → nothing
        if (b.length > 160000) {
            byte[] tail = new byte[160000];
            System.arraycopy(b, b.length - 160000, tail, 0, 160000);
            b = tail;
        }
        float[] f = new float[b.length / 2];
        for (int i = 0; i < f.length; i++) {
            int lo = b[2 * i] & 0xff, hi = b[2 * i + 1];   // hi keeps sign
            f[i] = ((short) ((hi << 8) | lo)) / 32768f;
        }
        OfflineStream st = null;
        try {
            long t0 = System.currentTimeMillis();
            st = rec.createStream();
            st.acceptWaveform(f, RATE);
            rec.decode(st);
            String text = rec.getResult(st).getText();
            Log.i(TAG, "decoded " + f.length + " samples (" + (f.length * 1000L / RATE) + "ms) in "
                    + (System.currentTimeMillis() - t0) + "ms: " + text);
            return text == null ? "" : text.trim();
        } catch (Throwable t) { Log.e(TAG, "finish", t); return ""; }
        finally { if (st != null) try { st.release(); } catch (Throwable ignored) {} }
    }

    private static void unpackAssets(String assetDir, File outDir) throws Exception {
        AssetManager am = appCtx.getAssets();
        String[] list = am.list(assetDir);
        if (list == null || list.length == 0) {   // it's a file
            outDir.getParentFile().mkdirs();
            InputStream in = am.open(assetDir);
            OutputStream out = new FileOutputStream(outDir);
            byte[] bb = new byte[1 << 16]; int n;
            while ((n = in.read(bb)) > 0) out.write(bb, 0, n);
            in.close(); out.close();
            return;
        }
        outDir.mkdirs();
        for (String name : list) unpackAssets(assetDir + "/" + name, new File(outDir, name));
    }

    private GigaAsr() {}
}