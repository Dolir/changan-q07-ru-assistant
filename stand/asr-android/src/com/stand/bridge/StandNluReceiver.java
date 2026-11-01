/*
 * Russian Voice Assistant modification for Changan A06 (C390).
 * Copyright (c) 2026 Tecrow.
 * Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only. See LICENSE.
 * Independent modification — not affiliated with or endorsed by Changan Automobile.
 */
package com.stand.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Test/dev trigger for the stand:
 *   am broadcast -a com.stand.NLU -p com.incall.apps.speechassistant --es json '{...arbitration...}'
 *     -> NluManager.onArbitrationResult(json)  (execute a car command)
 *   am broadcast -a com.stand.NLU -p com.incall.apps.speechassistant --es q 'текст'
 *     -> cloud dialog (chat)
 *   am broadcast -a com.stand.NLU -p com.incall.apps.speechassistant --es cmd 'открой окно водителя'
 *     -> mapCommand(phrase): known car command -> arbitration, else -> chat (same routing as live ASR)
 *   am broadcast -a com.stand.NLU -p com.incall.apps.speechassistant --es status 1
 *     -> pushStatus(): collect car status snapshot -> POST our backend /aibox/loadStatus
 *   am broadcast -a com.stand.NLU -p com.incall.apps.speechassistant --es dicts 1
 *     -> pushDicts(): POST personalized dictionaries -> our backend /aibox/loadDicts
 */
public class StandNluReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        String json = intent.getStringExtra("json");
        if (json != null && !json.isEmpty()) {
            Log.i("RuBridge", "trigger execArbitration");
            RuBridge.execArbitration(json);
            return;
        }
        String cmd = intent.getStringExtra("cmd");
        if (cmd != null && !cmd.isEmpty()) {
            Log.i("RuBridge", "trigger phrase: " + cmd);
            RuBridge.handlePhrase(cmd);
            return;
        }
        if (intent.hasExtra("keywake")) { Log.i("RuBridge", "trigger keyWakeTest"); RuBridge.keyWakeTest(); return; }
        if (intent.hasExtra("localfailed")) { Log.i("RuBridge", "trigger onLocalCommandFailed"); RuBridge.onLocalCommandFailed(); return; }
        if (intent.hasExtra("status")) { Log.i("RuBridge", "trigger pushStatus"); RuBridge.pushStatus(); return; }
        if (intent.hasExtra("dicts"))  { Log.i("RuBridge", "trigger pushDicts");  RuBridge.pushDicts();  return; }
        String diag = intent.getStringExtra("diag");
        if (diag != null && !diag.isEmpty()) { Log.i("RuBridge", "trigger diagDump"); RuBridge.diagDump(diag); return; }
        String dub = intent.getStringExtra("dubheb64");   // UTF-8 base64: send ZH straight to real Dubhe cloud
        if (dub != null && !dub.isEmpty()) {
            try {
                String dec = new String(android.util.Base64.decode(dub, android.util.Base64.DEFAULT), "UTF-8");
                Log.i("RuBridge", "trigger dubheTest(b64): " + dec);
                RuBridge.dubheTest(dec);
            } catch (Throwable t) { Log.e("RuBridge", "dubheb64", t); }
            return;
        }
        String ask = intent.getStringExtra("askb64");   // UTF-8 base64 question: spoken, then the assistant listens for the answer
        if (ask != null && !ask.isEmpty()) {
            try {
                RuBridge.askTest(new String(android.util.Base64.decode(ask, android.util.Base64.DEFAULT), "UTF-8"));
            } catch (Throwable t) { Log.e("RuBridge", "askb64", t); }
            return;
        }
        String sayStock = intent.getStringExtra("sayb64");   // UTF-8 base64 text → stock TTS route (GlobalTtsClient) for engine tests
        if (sayStock != null && !sayStock.isEmpty()) {
            try {
                String dec = new String(android.util.Base64.decode(sayStock, android.util.Base64.DEFAULT), "UTF-8");
                Log.i("RuBridge", "trigger speakTest: " + dec);
                RuBridge.speakTest(dec);
            } catch (Throwable t) { Log.e("RuBridge", "sayb64", t); }
            return;
        }
        String rub = intent.getStringExtra("rub64");   // UTF-8 base64 Russian: full ru2zh/MyMemory→cloud→RU loop
        if (rub != null && !rub.isEmpty()) {
            try {
                String dec = new String(android.util.Base64.decode(rub, android.util.Base64.DEFAULT), "UTF-8");
                Log.i("RuBridge", "trigger handlePhraseZh(b64): " + dec);
                RuBridge.handlePhraseZh(dec);
            } catch (Throwable t) { Log.e("RuBridge", "rub64", t); }
            return;
        }
        String zhc = intent.getStringExtra("zhcloudb64");   // ZH -> self-contained cloud ask (answer -> RU speak)
        if (zhc != null && !zhc.isEmpty()) {
            try {
                String dec = new String(android.util.Base64.decode(zhc, android.util.Base64.DEFAULT), "UTF-8");
                Log.i("RuBridge", "trigger cloudAsk(b64): " + dec);
                RuBridge.cloudAsk(dec);
            } catch (Throwable t) { Log.e("RuBridge", "zhcloudb64", t); }
            return;
        }
        String app = intent.getStringExtra("app64");   // UTF-8 base64 JSON array of appActions (test the launch gate)
        if (app != null && !app.isEmpty()) {
            try {
                String dec = new String(android.util.Base64.decode(app, android.util.Base64.DEFAULT), "UTF-8");
                Log.i("RuBridge", "trigger fireAppActions(b64): " + dec);
                RuBridge.fireAppActions(new org.json.JSONArray(dec));
            } catch (Throwable t) { Log.e("RuBridge", "app64", t); }
            return;
        }
        String zhb = intent.getStringExtra("zhb64");   // UTF-8 base64: shell-safe for CJK experiments
        if (zhb != null && !zhb.isEmpty()) {
            try {
                String dec = new String(android.util.Base64.decode(zhb, android.util.Base64.DEFAULT), "UTF-8");
                Log.i("RuBridge", "trigger injectZh(b64): " + dec);
                RuBridge.injectZh(dec);
            } catch (Throwable t) { Log.e("RuBridge", "zhb64", t); }
            return;
        }
        String zh = intent.getStringExtra("zh");
        if (zh != null && !zh.isEmpty()) { Log.i("RuBridge", "trigger injectZh"); RuBridge.injectZh(zh); return; }
        String ru = intent.getStringExtra("ru");
        if (ru != null && !ru.isEmpty()) { Log.i("RuBridge", "trigger ru2zh"); RuBridge.handlePhraseZh(ru); return; }
        String say = intent.getStringExtra("say");
        if (say != null && !say.isEmpty()) { Log.i("RuBridge", "trigger TeraTts.speak"); com.stand.tts.TeraTts.speak(say); return; }
        String q = intent.getStringExtra("q");
        if (q != null && !q.isEmpty()) {
            Log.i("RuBridge", "trigger chat: " + q);
            RuBridge.sendToCloud(q);
        }
    }
}