/*
 * Russian Voice Assistant modification for Changan A06 (C390).
 * Copyright (c) 2026 Tecrow.
 * Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only. See LICENSE.
 * Independent modification — not affiliated with or endorsed by Changan Automobile.
 */
package com.stand.asr;

import android.content.Context;
import android.util.Log;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * GigaAM-v3 CTC offline ASR backend. Runs on sherpa-onnx (the same native
 * libsherpa-onnx-jni.so we already bundle for Piper — it carries GigaAM `is_giga_am` fbank support).
 *
 * GigaAM is a full-utterance Conformer, NOT streaming: audio (16 kHz mono s16le) arrives as chunks
 * from the stock SR callback with a phase flag (wp==1 start .. wp==3 end). We accumulate the whole
 * utterance between wp==1 and wp==3, then run one greedy-CTC decode. sherpa computes the 64-bin
 * GigaAM log-mel (via the model's `is_giga_am` metadata) and the CTC decode internally, so no
 * hand-rolled feature extraction. The model (~224 MB int8) is not in the APK, see ModelStore.
 */
public final class GigaAsr {
    private static final String TAG = "GigaAsr";
    private static final String MODEL_DIR = "gigaam";   // model.int8.onnx, tokens.txt
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
            File dir = com.stand.bridge.ModelStore.dir(appCtx, MODEL_DIR);
            File onnx = new File(dir, MODEL), toks = new File(dir, TOKENS);
            if (!onnx.exists() || !toks.exists()) {
                Log.e(TAG, "model is missing in " + dir);
                return false;
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

    /** 16 kHz, 16-bit mono. */
    private static final int BYTES_PER_SECOND = 32000;
    /** The stock VAD reports a phrase late: 350 ms still lost the first sound. */
    private static final int PREROLL_SIZE = BYTES_PER_SECOND * 6 / 10;
    /** Decode time grows with it: about 2 s at the limit. At 5 s long phrases lost their start. */
    private static final int MAX_DECODE = BYTES_PER_SECOND * 15;
    private static final byte[] preroll = new byte[PREROLL_SIZE];
    private static int prerollHead = 0;
    private static int prerollCount = 0;

    /** Multilingual build (GigaAM-Multilingual CTC)? Only meaningful once the recognizer is ready. */
    public static boolean multilingual() { return multilingual; }

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

    // Outside recorder: 16 kHz, 6 channels (stock log: «PcmOutRecorder start channel=6»), 12-byte frames.
    private static final int OUT_CHANNELS = 6;
    private static final int OUT_FRAME = OUT_CHANNELS * 2;
    private static final int OUT_PREROLL = BYTES_PER_SECOND * OUT_CHANNELS;   // 1 s: outside the phrase start was still clipped at 300 ms
    private static final int OUT_LIMIT = MAX_DECODE * OUT_CHANNELS;
    private static byte[] outRaw = new byte[2 * OUT_LIMIT];
    private static int outLen = 0;

    /** Outside mics, raw. Which channel carries the voice is decided per phrase in {@link #loudestOutside}. */
    public static void acceptOutside(byte[] pcm, int len) {
        if (pcm == null || len <= 0 || com.stand.tts.TeraTts.isSpeaking()) return;
        int act = Math.min(len, pcm.length);
        synchronized (buf) {
            if (outLen + act > outRaw.length) keepOutside(OUT_LIMIT);
            if (act > outRaw.length - outLen) return;
            System.arraycopy(pcm, 0, outRaw, outLen, act);
            outLen += act;
        }
    }

    private static void keepOutside(int last) {
        int keep = Math.min(outLen, last) / OUT_FRAME * OUT_FRAME;
        System.arraycopy(outRaw, outLen - keep, outRaw, 0, keep);
        outLen = keep;
    }

    private static byte[] loudestOutside(byte[] raw) {
        long[] energy = new long[OUT_CHANNELS];
        int frames = raw.length / OUT_FRAME;
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < OUT_CHANNELS; c++) {
                int at = i * OUT_FRAME + c * 2;
                energy[c] += Math.abs((short) ((raw[at + 1] << 8) | (raw[at] & 0xff)));
            }
        }
        int best = 0;
        for (int c = 1; c < OUT_CHANNELS; c++) if (energy[c] > energy[best]) best = c;
        Log.i(TAG, "outside channels " + java.util.Arrays.toString(energy) + " -> " + best);
        byte[] mono = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            mono[i * 2] = raw[i * OUT_FRAME + best * 2];
            mono[i * 2 + 1] = raw[i * OUT_FRAME + best * 2 + 1];
        }
        return mono;
    }

    /** Start of a new utterance (wp==1): preserve pre-roll audio so initial consonants are never clipped. */
    public static void reset() {
        synchronized (buf) {
            keepOutside(OUT_PREROLL);
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
        if (pcm == null || len <= 0 || com.stand.tts.TeraTts.isSpeaking()) return;
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
            if (outLen > 0) {
                b = loudestOutside(java.util.Arrays.copyOf(outRaw, outLen));
                outLen = 0;
            }
        }
        if (b.length < RATE / 25 * 2) return "";   // <40 ms of audio → nothing
        if (b.length > MAX_DECODE) {
            byte[] tail = new byte[MAX_DECODE];
            System.arraycopy(b, b.length - MAX_DECODE, tail, 0, MAX_DECODE);
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

    private GigaAsr() {}
}