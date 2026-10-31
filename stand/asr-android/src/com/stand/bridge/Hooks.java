package com.stand.bridge;

import android.util.Log;

import com.incall.apps.speechassistant.common.VoiceStateCache;
import com.incall.apps.speechassistant.sr.SrSession;
import com.incall.apps.voiceservice.sr.bean.NlpBean;
import com.stand.asr.FeedTask;
import com.stand.asr.GigaAsr;

/**
 * Entry points of the smali hooks in build_q07.sh. A hook only calls a method here, the decisions live in
 * this class, so they can be fixed with a dex update, without rebuilding the APK.
 *
 * The signatures are a contract with every installed APK: do not change or remove them, add a new method
 * and a new hook instead. A failure must never break the stock flow, so each method falls back to stock.
 */
public final class Hooks {
    private static final String TAG = "RuBridge";
    private static final int SR_MSG_VAD = 0x451;
    /** The cabin frame is 8 channels of 16-bit samples, the voice is in the first channel. */
    private static final int CABIN_FRAME = 16;

    private Hooks() {}

    /** SrSession.startRecognize */
    public static void onStartRecognize() {
        try {
            FeedTask.reset();
            GigaAsr.reset();
        } catch (Throwable t) { Log.e(TAG, "onStartRecognize", t); }
    }

    /** SrSession.stopRecognize */
    public static void onStopRecognize() {
        try {
            Log.e("FEED3", "call-async");
            FeedTask.start();
        } catch (Throwable t) { Log.e(TAG, "onStopRecognize", t); }
    }

    /** SrSession.onSrMsg: the stock VAD starts a phrase and ends it. */
    public static void onSrMsg(int what, String msg) {
        try {
            if (what != SR_MSG_VAD || msg == null) return;
            if ("speech_start".equals(msg)) {
                FeedTask.reset();
                GigaAsr.reset();
                Log.i("STAND_VAD", "speech_start -> reset");
            } else if ("speech_end".equals(msg) || "speech_timeout".equals(msg)) {
                if (!VoiceStateCache.isWakeUp() && !VoiceStateCache.isOutSide()) {
                    Log.i("STAND_VAD", "speech_end/timeout ignored: assistant is asleep");
                    return;
                }
                Log.i("STAND_VAD", "speech_end/timeout -> FeedTask.start()");
                FeedTask.start();
            }
        } catch (Throwable t) { Log.e(TAG, "onSrMsg", t); }
    }

    /** SrSession.checkNlpResult: our commands skip FreeWakeManager. @return true to accept the result. */
    public static boolean acceptStandNlp(Object session, Object nlp) {
        try {
            if (nlp == null) return false;
            String sid = ((NlpBean) nlp).sid;
            if (sid == null || !sid.startsWith("stand")) return false;
            ((SrSession) session).clearResult();
            Log.i(TAG, "checkNlpResult: stand cmd accepted, bypassing FreeWakeManager!");
            return true;
        } catch (Throwable t) { Log.e(TAG, "acceptStandNlp", t); return false; }
    }

    /** SeEngineProxy.process: the cabin microphones. */
    public static void onCabinAudio(byte[] buf, int len) {
        try {
            if (VoiceStateCache.isOutSide()) return;
            byte[] mono = new byte[len];
            int out = 0;
            for (int i = 0; i < len; i += CABIN_FRAME) {
                mono[out] = buf[i];
                mono[out + 1] = buf[i + 1];
                out += 2;
            }
            GigaAsr.accept(mono, out);
        } catch (Throwable t) { Log.e(TAG, "onCabinAudio", t); }
    }

    /** OutSeEngineProxy.process: the outside microphones, raw 6-channel PCM. */
    public static void onOutsideAudio(byte[] buf, int len) {
        try {
            if (VoiceStateCache.isOutSide()) GigaAsr.acceptOutside(buf, len);
        } catch (Throwable t) { Log.e(TAG, "onOutsideAudio", t); }
    }

    /** NluManager.onArbitrationResult. @return false to drop the result. */
    public static boolean onArbitrationResult(String json) {
        try {
            if (!RuBridge.isOurs(json) && VoiceStateCache.isOutSide()) {
                Log.i(TAG, "onArbitrationResult: dropped non-ours (Chinese) NLU result");
                return false;
            }
            RuBridge.checkArbResult(json);
        } catch (Throwable t) { Log.e(TAG, "onArbitrationResult", t); }
        return true;
    }

    /** ArbitrationManager.arbitrationNluResult: our commands don't wait for other engines. */
    public static boolean isStandRequest(String requestId) {
        return requestId != null && requestId.startsWith("stand");
    }

    /** TipsManager.getWakeUpTipsFromClick */
    public static String clickWakeTip() {
        return "Слушаю";
    }

    /** PgsController.updateText: Russian text instead of the stock one, while we have it. */
    public static String updateText(String original) {
        try {
            String ru = RuBridge.swap(original);
            return ru.isEmpty() ? original : ru;
        } catch (Throwable t) { Log.e(TAG, "updateText", t); return original; }
    }
}
