/*
 * Russian Voice Assistant modification for Changan A06 (C390).
 * Copyright (c) 2026 Tecrow.
 * Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only. See LICENSE.
 * Independent modification — not affiliated with or endorsed by Changan Automobile.
 */
package com.stand.tts;

import android.util.Log;
import com.incall.voicecore.tts.ICaStreamTts;
import com.incall.voicecore.tts.ICaTtsCallback;

/**
 * Piper-backed NATIVE TTS engine. Registered in the factory engine registry
 * (/resources/iflytek/speech/changan_assets/tts_config.txt) as a supplier, so the stock
 * StreamTtsEngineProxy drives it exactly like the iFlytek/Changan engines — no pipeline change.
 *
 * Contract (from decompiled ICaStreamTts / TtsAbsEngine):
 *   create(cb)         store the PCM sink
 *   startSession(sid)  begin an utterance session
 *   sendText(text)     synthesize (RU via sherpa-Piper) -> 16-bit PCM -> cb.onAudioData(chunk,len)
 *   endSession()       cb.onMessage(2) = onComplete
 *   onMessage(1)=begin, onMessage(2)=complete (TtsAbsEngine maps these to the player's onBegin/onComplete).
 *
 * Chinese tips ("主驾请说") are translated to Russian here (RuBridge.ttsTextToRu); command replies
 * arrive already Russian from the ru.lang overlays. Degree/unit/gender normalization runs in synthPcm16.
 */
public class PiperCaTts implements ICaStreamTts {
    private static final String TAG = "PiperCaTts";
    private static final int RATE = 24000;      // stock player rate: TtsAudioManager.SAMPLE_RATE_DEFAULT=0x5dc0
    private static final int CHUNK = 4800;      // ~100 ms @ 24 kHz * 2 bytes

    private static final boolean TONE_TEST = false;  // DIAGNOSTIC: emit a 440 Hz beep instead of speech.
    private static final boolean WAKE_CHIME = true;  // wake greeting → stock wakeup.wav chime instead of TTS phrase
    private static volatile android.content.Context appCtx;
    private volatile ICaTtsCallback cb;
    private volatile boolean stopReq;
    private volatile boolean begun;

    /** 600 ms 440 Hz sine as 16-bit mono PCM at RATE — an unmistakable diagnostic tone. */
    private static byte[] tone() {
        int n = (int) (RATE * 0.6);
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int v = (int) (Math.sin(2 * Math.PI * 440 * i / RATE) * 12000);
            pcm[i * 2] = (byte) (v & 0xff); pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        return pcm;
    }

    public PiperCaTts() { Log.i(TAG, "ctor"); }

    @Override public int create(ICaTtsCallback c) { cb = c; Log.i(TAG, "create"); return 0; }

    @Override public int startSession(String sid) {
        stopReq = false; begun = false;
        Log.i(TAG, "startSession " + sid);
        return 0;
    }

    @Override public int sendText(String text) {
        final ICaTtsCallback c = cb;
        Log.i(TAG, "TTS-IN raw=[" + text + "] len=" + (text == null ? -1 : text.length()));
        if (c == null || text == null || text.trim().isEmpty()) return 0;
        // Owner's rule: our TTS never voices Chinese. A pure-CJK request (stock NLG in Mandarin) is
        // dropped silently instead of translated+spoken. Mixed RU+CJK still speaks (handled below).
        if (com.stand.bridge.RuBridge.isChineseSpeech(text)) { Log.i(TAG, "sendText: IGNORED Chinese TTS request: [" + text + "]"); return 0; }
        try {
            if (TONE_TEST) { push(c, tone()); Log.i(TAG, "sendText TONE for: " + text); return 0; }
            String ru = com.stand.bridge.RuBridge.ttsTextToRu(text);  // ZH->RU (tips), Cyrillic passthrough
            if (ru == null || ru.trim().isEmpty()) { Log.i(TAG, "sendText untranslatable: " + text); return 0; }
            Log.i(TAG, "sendText: [" + text + "] -> " + ru);
            // Steering-key/knob wake: build_sa (WAKE_CHIME) makes the click tip a fixed marker «Слушаю»;
            // play the chime for it instead of speech so the driver can talk right away (SR is already
            // recording — startRec precedes the tip). Voice wake keeps its spoken «Чем могу помочь».
            byte[] chime = WAKE_CHIME && com.stand.bridge.RuBridge.isClickWakeMarker(ru) ? WakeChime.pcm16(appCtx, RATE) : null;
            if (chime != null) { Log.i(TAG, "key-wake marker -> chime (" + chime.length + " bytes)"); push(c, chime); return 0; }
            if (WAKE_CHIME && com.stand.bridge.RuBridge.isWakeGreeting(ru)) {
                // Voice wake («Чем могу помочь»): owner wants the same chime played IN PARALLEL with the
                // spoken greeting — mix the two PCM streams (both from t=0) into one utterance.
                byte[] pcm = TeraTts.synthPcm16(ru, RATE);
                byte[] ch = WakeChime.pcm16(appCtx, RATE);
                if (ch != null) { pcm = WakeChime.mix(pcm, ch); Log.i(TAG, "voice-wake greeting + chime mixed (" + pcm.length + " bytes)"); }
                push(c, pcm); return 0;
            }
            // Sentence by sentence, in the native streaming contract: the first sentence is synthesized
            // and handed to the stock player at once (it starts playing as chunks arrive), the next ones
            // are synthesized while it plays. Synthesis is faster than playback, so speech is continuous
            // and a two-sentence answer starts after ~1 s instead of after the whole synthesis.
            String[] parts = TeraTts.sentences(ru);
            int total = 0;
            for (int i = 0; i < parts.length && !stopReq; i++) {
                long t0 = System.currentTimeMillis();
                byte[] pcm = TeraTts.synthPcm16(parts[i], RATE);
                if (pcm.length == 0) { Log.w(TAG, "sendText: empty synth for sentence " + (i + 1) + ": " + parts[i]); continue; }
                Log.i(TAG, "sentence " + (i + 1) + "/" + parts.length + " synth " + (System.currentTimeMillis() - t0) + "ms, " + pcm.length + " b");
                total += push(c, pcm);
            }
            Log.i(TAG, "pushed " + total + " bytes @" + RATE + " (" + parts.length + " sentence(s))");
        } catch (Throwable t) { Log.e(TAG, "sendText", t); }
        return 0;
    }

    /** Hand one PCM buffer to the stock player in ~100 ms chunks (onBegin once per session). */
    private int push(ICaTtsCallback c, byte[] pcm) {
        if (pcm == null || pcm.length == 0 || stopReq) return 0;
        if (!begun) { begun = true; c.onMessage(1, ""); }          // onBegin
        int total = 0, lastRet = 0;
        for (int off = 0; off < pcm.length && !stopReq; off += CHUNK) {
            int n = Math.min(CHUNK, pcm.length - off);
            byte[] b = new byte[n];
            System.arraycopy(pcm, off, b, 0, n);
            lastRet = c.onAudioData(b, n);
            total += n;
        }
        if (lastRet != 0) Log.w(TAG, "onAudioData ret=" + lastRet);
        return total;
    }

    @Override public int sendText(String text, String param) { return sendText(text); }

    @Override public int endSession() {
        Log.i(TAG, "endSession" + (begun ? "" : " (nothing was spoken)"));
        ICaTtsCallback c = cb;
        if (c != null) {
            try {
                // A session that pushed NO audio (Chinese request dropped, untranslatable text, an empty
                // rewrite of a suppressed guide word) never sent onBegin, so the stock TtsPlayer is still
                // in START and its onComplete does compareAndSet(PLAYING -> IDLE), which FAILS: it returns
                // early and neither releases the audio focus nor delivers onPlayComplete to the caller —
                // the turn stays open and the focus counter drifts. Send begin first so the player walks
                // START -> PLAYING -> IDLE and finishes the turn cleanly even with zero audio.
                if (!begun) { begun = true; c.onMessage(1, ""); }
                c.onMessage(2, "");
            } catch (Throwable ignored) {}
        }
        begun = false;
        return 0;
    }

    // Non-streaming entry (some callers use ICaTts.start directly): one-shot session.
    @Override public int start(String text, String param, boolean flag, ICaTtsCallback c) {
        if (c != null) cb = c;
        startSession("start"); sendText(text); endSession();
        return 0;
    }

    @Override public int stop() {
        stopReq = true; Log.i(TAG, "stop");
        ICaTtsCallback c = cb;
        if (c != null && begun) { try { c.onMessage(2, ""); } catch (Throwable ignored) {} }
        begun = false;
        return 0;
    }

    // ICaBase — setResDir is required (abstract); setContext gives us the real app Context for Piper.
    @Override public void setResDir(String dir) { Log.i(TAG, "setResDir " + dir); }
    @Override public void setContext(android.content.Context c) {
        if (c != null) appCtx = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        try { if (c != null) TeraTts.init(c); } catch (Throwable ignored) {}
    }
    @Override public void setVin(String vin) {}

    @Override public int pause()  { return 0; }
    @Override public int resume() { return 0; }
    @Override public int destroy(){ stopReq = true; cb = null; Log.i(TAG, "destroy"); return 0; }
    @Override public int setParam(String key, String value) { return 0; }
}