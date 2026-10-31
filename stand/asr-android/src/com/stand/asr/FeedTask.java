package com.stand.asr;

import android.util.Log;

import com.incall.apps.speechassistant.common.VoiceStateCache;
import com.stand.bridge.RuBridge;

/** Decodes the collected phrase once per phrase, off the thread of the stock recognizer. */
public final class FeedTask implements Runnable {
    private static boolean isFeeding;

    public static void reset() {
        isFeeding = false;
    }

    public static synchronized void start() {
        if (isFeeding) return;
        isFeeding = true;
        new Thread(new FeedTask(), "stand-feed3").start();
    }

    @Override public void run() {
        doFeed();
    }

    private static synchronized void doFeed() {
        if (!VoiceStateCache.isWakeUp() && !VoiceStateCache.isOutSide()) {
            Log.i("FeedTask", "doFeed skipped: assistant asleep (not awake, not outside)");
            return;
        }
        RuBridge.feed(null, 0, 0, 3L, null, 0);
    }
}
